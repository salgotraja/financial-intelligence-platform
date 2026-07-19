package dev.engnotes.dsr.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/**
 * Right-to-erasure cascade (generalizes consent-function's withdrawal purge). Deletes the subject's
 * {@code CONSENT} record + every {@code WATCH#{ticker}} item + its {@code WATCHSET/TICKER#{ticker}}
 * mirror from the platform table, then deletes the Cognito identity. DynamoDB deletes run before the
 * Cognito call (the only irreversible step). The audit trail is on a separate table and is untouched.
 * Idempotent and re-runnable: re-running an already-erased subject deletes the CONSENT key again
 * (no-op) and finds no Cognito user.
 *
 * <p>{@link #deleteUserItems} is the DynamoDB-item cascade's shared core, invoked by
 * {@link dev.engnotes.dsr.service.ErasureService#erase}. {@link #s3Safeguard} is a documented no-op:
 * the data lake holds ticker/price time-series only, no subject-linked keys, and this module has no S3
 * client or grant to act on regardless.
 *
 * <p>{@link #acquireDeletionLease} (Step Functions collapsed 2026-07-19) guards the inline call-chain
 * cascade against concurrent duplicate requests: a conditional put that only a fresh cascade can win,
 * so a concurrent duplicate request is turned away and a crashed cascade's stale lease is taken over on
 * the next request. {@link #clearDeletionPending} clears that lease last.
 */
@Service
public class UserErasureService {

    private static final Logger log = LoggerFactory.getLogger(UserErasureService.class);
    static final Duration DELETION_LEASE = Duration.ofMinutes(5);

    private final DynamoDbClient dynamoDb;
    private final CognitoUserService cognito;
    private final String platformTable;
    private final Clock clock;

    public UserErasureService(
            DynamoDbClient dynamoDb,
            CognitoUserService cognito,
            @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable,
            Clock clock) {
        this.dynamoDb = dynamoDb;
        this.cognito = cognito;
        this.platformTable = platformTable;
        this.clock = clock;
    }

    /**
     * Acquires the deletion-pending lease: writes {@code USER#{sub}/PROFILE} with {@code
     * deletionPending=true} on condition that no fresh cascade holds it (flag absent, false, or older
     * than the 5-minute lease). The winner runs the cascade, a concurrent duplicate gets {@code false},
     * and a crashed cascade's stale lease is taken over on the next request.
     */
    public boolean acquireDeletionLease(String subjectSub, String requestedAt) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValues.s("USER#" + subjectSub));
        item.put("SK", AttributeValues.s("PROFILE"));
        item.put("deletionPending", AttributeValue.builder().bool(true).build());
        item.put("requestedAt", AttributeValues.s(requestedAt));
        String staleCutoff = Instant.now(clock).minus(DELETION_LEASE).toString();
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(platformTable)
                    .item(item)
                    .conditionExpression("attribute_not_exists(deletionPending) OR deletionPending <> :pending"
                            + " OR requestedAt < :staleCutoff")
                    .expressionAttributeValues(Map.of(
                            ":pending", AttributeValue.builder().bool(true).build(),
                            ":staleCutoff", AttributeValues.s(staleCutoff)))
                    .build());
            log.info("Acquired deletion lease. subjectSub={}", subjectSub);
            return true;
        } catch (ConditionalCheckFailedException e) {
            log.info("Deletion lease held by an in-flight cascade; no-op. subjectSub={}", subjectSub);
            return false;
        }
    }

    /** Clears the deletion-pending flag. Idempotent: deleting an absent key is a no-op. */
    public void clearDeletionPending(String subjectSub) {
        dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(platformTable)
                .key(Map.of("PK", AttributeValues.s("USER#" + subjectSub), "SK", AttributeValues.s("PROFILE")))
                .build());
        log.info("Cleared deletion-pending flag. subjectSub={}", subjectSub);
    }

    /**
     * Deletes the subject's {@code CONSENT} record and every {@code WATCH#{ticker}} item plus its
     * {@code WATCHSET/TICKER#{ticker}} mirror. Idempotent and re-runnable: an absent CONSENT/WATCH item
     * is simply not returned by the query, so a retry after partial success deletes only what remains.
     * Returns the count of items deleted.
     */
    public int deleteUserItems(String subjectSub) {
        List<String> tickers = dynamoDb
                .queryPaginator(QueryRequest.builder()
                        .tableName(platformTable)
                        .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                        .expressionAttributeValues(Map.of(
                                ":pk", AttributeValues.s("USER#" + subjectSub), ":sk", AttributeValues.s("WATCH#")))
                        .build())
                .items()
                .stream()
                .map(item -> item.get("ticker"))
                .filter(Objects::nonNull)
                .map(AttributeValue::s)
                .toList();

        List<WriteRequest> writes = new ArrayList<>();
        writes.add(deleteOf(Map.of("PK", AttributeValues.s("USER#" + subjectSub), "SK", AttributeValues.s("CONSENT"))));
        for (String ticker : tickers) {
            writes.add(deleteOf(
                    Map.of("PK", AttributeValues.s("USER#" + subjectSub), "SK", AttributeValues.s("WATCH#" + ticker))));
            writes.add(
                    deleteOf(Map.of("PK", AttributeValues.s("WATCHSET"), "SK", AttributeValues.s("TICKER#" + ticker))));
        }

        DynamoBatch.batchWriteAllWithRetry(dynamoDb, platformTable, writes);
        return writes.size();
    }

    /**
     * Documented no-op safeguard step: the S3 data lake holds ticker/price time-series only, never
     * subject-linked keys, so there is nothing to scan or delete. This module carries no S3 client or
     * grant; a real tagged-object scan-and-delete would need both.
     */
    public void s3Safeguard(String subjectSub) {
        log.info("S3 safeguard no-op: data lake holds no subject-linked keys. subjectSub={}", subjectSub);
    }

    private static WriteRequest deleteOf(Map<String, AttributeValue> key) {
        return WriteRequest.builder()
                .deleteRequest(DeleteRequest.builder().key(key).build())
                .build();
    }
}
