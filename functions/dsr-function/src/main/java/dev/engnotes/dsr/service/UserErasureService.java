package dev.engnotes.dsr.service;

import dev.engnotes.dsr.model.ErasureResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/**
 * Right-to-erasure cascade (generalizes consent-function's withdrawal purge). Deletes the subject's
 * {@code CONSENT} record + every {@code WATCH#{ticker}} item + its {@code WATCHSET/TICKER#{ticker}}
 * mirror from the platform table, then deletes the Cognito identity. DynamoDB deletes run before the
 * Cognito call (the only irreversible step). The audit trail is on a separate table and is untouched.
 * Idempotent and re-runnable: re-running an already-erased subject deletes the CONSENT key again
 * (no-op) and finds no Cognito user.
 */
@Service
public class UserErasureService {

    private static final Logger log = LoggerFactory.getLogger(UserErasureService.class);

    private final DynamoDbClient dynamoDb;
    private final CognitoUserService cognito;
    private final String platformTable;

    public UserErasureService(
            DynamoDbClient dynamoDb,
            CognitoUserService cognito,
            @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable) {
        this.dynamoDb = dynamoDb;
        this.cognito = cognito;
        this.platformTable = platformTable;
    }

    public ErasureResult erase(String subjectSub) {
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
