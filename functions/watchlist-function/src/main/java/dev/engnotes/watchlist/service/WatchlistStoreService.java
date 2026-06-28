package dev.engnotes.watchlist.service;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Maintains the per-user watchlist and the distinct-ticker union on the single table (spec section
 * 4). Each tracked ticker is two items: the user's item {@code USER#{sub} / WATCH#{ticker}} (in-scope
 * personal data, deletable by the future erasure prefix query) and the union entry
 * {@code WATCHSET / TICKER#{ticker}} read by the ingestion fan-out. Writes are idempotent.
 *
 * <p>{@code ownerSub} is a fixed placeholder until Cognito lands; the keying is the final spec shape,
 * so the only later change is sourcing {@code sub} from the JWT claim instead of the env var.
 */
@Service
public class WatchlistStoreService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistStoreService.class);

    private final DynamoDbClient dynamoDb;
    private final String platformTable;
    private final String ownerSub;

    public WatchlistStoreService(
            DynamoDbClient dynamoDb,
            @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable,
            @Value("${DEFAULT_OWNER_SUB:dev-user}") String ownerSub) {
        this.dynamoDb = dynamoDb;
        this.platformTable = platformTable;
        this.ownerSub = ownerSub;
    }

    /** Adds the ticker to the user's watchlist and the distinct-ticker union. Idempotent. */
    public void add(String ticker) {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(platformTable)
                .item(Map.of(
                        "PK", s("USER#" + ownerSub),
                        "SK", s("WATCH#" + ticker),
                        "ticker", s(ticker)))
                .build());
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(platformTable)
                .item(Map.of(
                        "PK", s("WATCHSET"),
                        "SK", s("TICKER#" + ticker),
                        "ticker", s(ticker)))
                .build());
        log.info("Added ticker to watchlist. ticker={} owner={}", ticker, ownerSub);
    }

    /**
     * Removes the ticker from the user's watchlist and the union. With a single owner the union
     * entry is removed unconditionally; multi-user "remove only if no other watcher" is a deferred
     * refinement for the Cognito phase.
     */
    public void remove(String ticker) {
        dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(platformTable)
                .key(Map.of("PK", s("USER#" + ownerSub), "SK", s("WATCH#" + ticker)))
                .build());
        dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(platformTable)
                .key(Map.of("PK", s("WATCHSET"), "SK", s("TICKER#" + ticker)))
                .build());
        log.info("Removed ticker from watchlist. ticker={} owner={}", ticker, ownerSub);
    }

    /** Lists the tickers on the user's watchlist (PK=USER#{sub}, SK begins_with WATCH#). */
    public List<String> list() {
        QueryRequest request = QueryRequest.builder()
                .tableName(platformTable)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                .expressionAttributeValues(Map.of(
                        ":pk", s("USER#" + ownerSub),
                        ":sk", s("WATCH#")))
                .build();
        return dynamoDb.query(request).items().stream()
                .map(item -> item.get("ticker"))
                .filter(value -> value != null)
                .map(AttributeValue::s)
                .toList();
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
