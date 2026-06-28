package dev.engnotes.consent.service;

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
 * Purges a user's watchlist on consent withdrawal (spec decision 6): deletes each
 * {@code USER#{sub}/WATCH#{ticker}} item and its {@code WATCHSET/TICKER#{ticker}} mirror. Owned by
 * consent (a DPDP data-lifecycle concern, the seed of sub-project C's erasure), reusing the fixed
 * key construction rather than coupling to a shared module. Idempotent and re-runnable: an empty
 * watchlist is a no-op. Single-owner WATCHSET pruning is unconditional, consistent with
 * WatchlistStoreService (multi-user "remove-if-still-watched" is deferred there too). The ticker
 * query paginates and the deletes drain UnprocessedItems via {@link DynamoBatch}.
 */
@Service
public class UserWatchlistPurgeService {

    private static final Logger log = LoggerFactory.getLogger(UserWatchlistPurgeService.class);

    private final DynamoDbClient dynamoDb;
    private final String platformTable;

    public UserWatchlistPurgeService(
            DynamoDbClient dynamoDb, @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable) {
        this.dynamoDb = dynamoDb;
        this.platformTable = platformTable;
    }

    public void purge(String sub) {
        List<String> tickers = dynamoDb
                .queryPaginator(QueryRequest.builder()
                        .tableName(platformTable)
                        .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                        .expressionAttributeValues(Map.of(":pk", s("USER#" + sub), ":sk", s("WATCH#")))
                        .build())
                .items()
                .stream()
                .map(item -> item.get("ticker"))
                .filter(Objects::nonNull)
                .map(AttributeValue::s)
                .toList();

        if (tickers.isEmpty()) {
            log.info("Watchlist purge no-op (empty). sub={}", sub);
            return;
        }

        List<WriteRequest> writes = new ArrayList<>();
        for (String ticker : tickers) {
            writes.add(deleteOf(Map.of("PK", s("USER#" + sub), "SK", s("WATCH#" + ticker))));
            writes.add(deleteOf(Map.of("PK", s("WATCHSET"), "SK", s("TICKER#" + ticker))));
        }

        DynamoBatch.batchWriteAllWithRetry(dynamoDb, platformTable, writes);
        log.info("Purged watchlist. sub={} tickers={}", sub, tickers.size());
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
