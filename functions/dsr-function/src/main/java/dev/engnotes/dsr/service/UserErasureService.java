package dev.engnotes.dsr.service;

import dev.engnotes.dsr.model.ErasureResult;
import java.time.Clock;
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
 * <p>Spec s11 erasure step 1: {@link #setDeletionPending} writes {@code USER#{sub}/PROFILE}
 * ({@code deletionPending=true}, {@code requestedAt}) before any delete, so every gated write path
 * (watchlist add, consent grant, WebSocket connect/subscribe) refuses for the duration of the
 * cascade. {@link #clearDeletionPending} deletes that item last. Both are public and independently
 * invokable (each a Step Functions state in Task 11) and idempotent: the put overwrites, the delete
 * of an absent key is a no-op.
 */
@Service
public class UserErasureService {

    private static final Logger log = LoggerFactory.getLogger(UserErasureService.class);

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
        item.put("PK", s("USER#" + subjectSub));
        item.put("SK", s("PROFILE"));
        item.put("deletionPending", AttributeValue.builder().bool(true).build());
        item.put("requestedAt", s(Instant.now(clock).toString()));
        dynamoDb.putItem(
                PutItemRequest.builder().tableName(platformTable).item(item).build());
        log.info("Set deletion-pending flag. subjectSub={}", subjectSub);
    }

    /** Clears the deletion-pending flag. Idempotent: deleting an absent key is a no-op. */
    public void clearDeletionPending(String subjectSub) {
        dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(platformTable)
                .key(Map.of("PK", s("USER#" + subjectSub), "SK", s("PROFILE")))
                .build());
        log.info("Cleared deletion-pending flag. subjectSub={}", subjectSub);
    }

    public ErasureResult erase(String subjectSub) {
        setDeletionPending(subjectSub);

        List<String> tickers = dynamoDb
                .queryPaginator(QueryRequest.builder()
                        .tableName(platformTable)
                        .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                        .expressionAttributeValues(Map.of(":pk", s("USER#" + subjectSub), ":sk", s("WATCH#")))
                        .build())
                .items()
                .stream()
                .map(item -> item.get("ticker"))
                .filter(Objects::nonNull)
                .map(AttributeValue::s)
                .toList();

        List<WriteRequest> writes = new ArrayList<>();
        writes.add(deleteOf(Map.of("PK", s("USER#" + subjectSub), "SK", s("CONSENT"))));
        for (String ticker : tickers) {
            writes.add(deleteOf(Map.of("PK", s("USER#" + subjectSub), "SK", s("WATCH#" + ticker))));
            writes.add(deleteOf(Map.of("PK", s("WATCHSET"), "SK", s("TICKER#" + ticker))));
        }

        DynamoBatch.batchWriteAllWithRetry(dynamoDb, platformTable, writes);

        boolean cognitoDeleted = cognito.deleteBySub(subjectSub);

        clearDeletionPending(subjectSub);

        log.info("Erased subject. subjectSub={} items={} cognitoDeleted={}", subjectSub, writes.size(), cognitoDeleted);
        return new ErasureResult("erased", subjectSub, writes.size(), cognitoDeleted);
    }

    private static WriteRequest deleteOf(Map<String, AttributeValue> key) {
        return WriteRequest.builder()
                .deleteRequest(DeleteRequest.builder().key(key).build())
                .build();
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
