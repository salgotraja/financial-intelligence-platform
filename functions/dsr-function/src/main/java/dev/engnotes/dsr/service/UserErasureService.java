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
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
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
 * <p>Spec s11 erasure step 1: {@link #setDeletionPending} writes {@code USER#{sub}/PROFILE}
 * ({@code deletionPending=true}, {@code requestedAt}) before any delete, so every gated write path
 * (watchlist add, consent grant) refuses for the duration of the
 * cascade. {@link #clearDeletionPending} deletes that item last. Both are public and independently
 * invokable (each a Step Functions state in Task 11) and idempotent: the put overwrites, the delete
 * of an absent key is a no-op.
 *
 * <p>Task 11 decomposes the DynamoDB-item cascade itself into {@link #deleteUserItems}, the {@code
 * DELETE_USER_ITEMS} Step Functions state's shared core. {@link #isDeletionPending} backs the
 * workflow-start idempotency check (an already-pending subject is a no-op success, no second
 * execution). {@link #s3Safeguard} is a documented no-op: the data lake holds ticker/price time-series
 * only, no subject-linked keys, and this module has no S3 client or grant to act on regardless.
 *
 * <p>{@link #acquireDeletionLease} replaces the Step Functions deterministic-execution-name
 * idempotency (removed 2026-07-19) for the inline call-chain cascade: a conditional put that only a
 * fresh cascade can win, so a concurrent duplicate request is turned away and a crashed cascade's
 * stale lease is taken over on the next request. {@link #setDeletionPending} and {@link
 * #isDeletionPending} remain in place only until their remaining Step Functions-era callers
 * ({@link dev.engnotes.dsr.DsrHandler}, {@link ErasureWorkflowService}) are removed in a later task.
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

    /** Marks the subject deletion-pending. Idempotent: a Put overwrites any existing flag. */
    public void setDeletionPending(String subjectSub) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValues.s("USER#" + subjectSub));
        item.put("SK", AttributeValues.s("PROFILE"));
        item.put("deletionPending", AttributeValue.builder().bool(true).build());
        item.put("requestedAt", AttributeValues.s(Instant.now(clock).toString()));
        dynamoDb.putItem(
                PutItemRequest.builder().tableName(platformTable).item(item).build());
        log.info("Set deletion-pending flag. subjectSub={}", subjectSub);
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

    /** True when the subject's PROFILE item carries {@code deletionPending=true}. */
    public boolean isDeletionPending(String subjectSub) {
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(platformTable)
                        .key(Map.of("PK", AttributeValues.s("USER#" + subjectSub), "SK", AttributeValues.s("PROFILE")))
                        .build())
                .item();
        AttributeValue flag = item == null ? null : item.get("deletionPending");
        return flag != null && Boolean.TRUE.equals(flag.bool());
    }

    /**
     * Documented no-op safeguard step (spec s11 {@code S3_SAFEGUARD}): the S3 data lake holds
     * ticker/price time-series only, never subject-linked keys, so there is nothing to scan or delete.
     * This module carries no S3 client or grant; a real tagged-object scan-and-delete would need both.
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
