package dev.engnotes.notifier.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
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
 * Subscription rows for WebSocket connections: PK=ticker, SK=connectionId, plus a TTL safety net
 * (~2h) for connections that die without a clean $disconnect. Disconnect looks the connection's
 * rows up via the by-connection GSI so cleanup never scans.
 *
 * <p>Tickers are validated against the platform's strict allowlist before they reach a key
 * expression (spec section 12); the exception message keeps the "Invalid ticker" prefix used
 * platform-wide.
 */
@Service
public class ConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    private static final Pattern TICKER_PATTERN = Pattern.compile("^[A-Z0-9.^-]{1,15}$");
    private static final long SUBSCRIPTION_TTL_SECONDS = 2 * 60 * 60;
    private static final String BY_CONNECTION_INDEX = "by-connection";
    private static final int MAX_TICKERS_PER_SUBSCRIBE = 25;

    private final DynamoDbClient dynamoDb;
    private final String connectionsTable;

    public ConnectionRegistry(
            DynamoDbClient dynamoDb, @Value("${CONNECTIONS_TABLE:financial-connections-dev}") String connectionsTable) {
        this.dynamoDb = dynamoDb;
        this.connectionsTable = connectionsTable;
    }

    /** Registers the connection for each ticker; returns the number of rows written. */
    public int subscribe(String connectionId, List<String> tickers) {
        if (tickers.size() > MAX_TICKERS_PER_SUBSCRIBE) {
            throw new IllegalArgumentException(
                    "Too many tickers: " + tickers.size() + " (max " + MAX_TICKERS_PER_SUBSCRIBE + ")");
        }
        for (String ticker : tickers) {
            if (ticker == null || !TICKER_PATTERN.matcher(ticker).matches()) {
                throw new IllegalArgumentException("Invalid ticker: " + ticker);
            }
        }
        long ttl = Instant.now().getEpochSecond() + SUBSCRIPTION_TTL_SECONDS;
        for (String ticker : tickers) {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(connectionsTable)
                    .item(Map.of(
                            "ticker", AttributeValue.builder().s(ticker).build(),
                            "connectionId",
                                    AttributeValue.builder().s(connectionId).build(),
                            "ttl",
                                    AttributeValue.builder()
                                            .n(String.valueOf(ttl))
                                            .build()))
                    .build());
        }
        log.info("Subscribed. connectionId={} tickers={}", connectionId, tickers.size());
        return tickers.size();
    }

    /** Removes every subscription row for the connection; returns the number deleted. */
    public int disconnect(String connectionId) {
        var rows = dynamoDb.query(QueryRequest.builder()
                        .tableName(connectionsTable)
                        .indexName(BY_CONNECTION_INDEX)
                        .keyConditionExpression("connectionId = :cid")
                        .expressionAttributeValues(Map.of(
                                ":cid", AttributeValue.builder().s(connectionId).build()))
                        .build())
                .items();
        for (Map<String, AttributeValue> row : rows) {
            dynamoDb.deleteItem(DeleteItemRequest.builder()
                    .tableName(connectionsTable)
                    .key(Map.of(
                            "ticker", row.get("ticker"),
                            "connectionId", row.get("connectionId")))
                    .build());
        }
        log.info("Disconnected. connectionId={} rowsDeleted={}", connectionId, rows.size());
        return rows.size();
    }
}
