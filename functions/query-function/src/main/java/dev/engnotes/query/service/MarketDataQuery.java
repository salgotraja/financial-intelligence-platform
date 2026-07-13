package dev.engnotes.query.service;

import dev.engnotes.query.model.MarketDataPoint;
import dev.engnotes.query.model.MarketDataResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Read-only serving of recent market-data points for a ticker (chart data for the frontend).
 *
 * <p>Design: the ingestion path stores observations with PK=TICKER#{ticker}, SK=TS#{iso8601} and a
 * 24h TTL. "Recent points" is a query on PK=TICKER#{ticker}, SK begins_with TS#, sorted descending,
 * Limit 50, so the response is newest-first and naturally bounded by the TTL window. This path
 * never writes, matching the read-only IAM grant on the market-data Lambda.
 *
 * <p>The ticker is validated against the same strict allowlist as the insight route before it
 * reaches the DynamoDB key expression (spec section 12); the exception message must keep the
 * "Invalid ticker" prefix that QueryStack's 400 selection pattern matches.
 */
@Service
public class MarketDataQuery {

    private static final Logger log = LoggerFactory.getLogger(MarketDataQuery.class);

    private static final int MAX_POINTS = 50;

    private final DynamoDbClient dynamoDb;
    private final String platformTable;

    public MarketDataQuery(
            DynamoDbClient dynamoDb, @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable) {
        this.dynamoDb = dynamoDb;
        this.platformTable = platformTable;
    }

    public MarketDataResponse findRecentPoints(String rawTicker) {
        String ticker = Tickers.validated(rawTicker);

        var request = software.amazon.awssdk.services.dynamodb.model.QueryRequest.builder()
                .tableName(platformTable)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.builder().s("TICKER#" + ticker).build(),
                        ":sk", AttributeValue.builder().s("TS#").build()))
                .scanIndexForward(false) // newest timestamp first
                .limit(MAX_POINTS)
                .build();

        List<Map<String, AttributeValue>> items = dynamoDb.query(request).items();

        if (items.isEmpty()) {
            log.info("No market data found. ticker={}", ticker);
            return MarketDataResponse.notFound(ticker);
        }

        List<MarketDataPoint> points =
                items.stream().map(MarketDataQuery::toPoint).toList();
        return new MarketDataResponse(ticker, points, true);
    }

    private static MarketDataPoint toPoint(Map<String, AttributeValue> item) {
        return new MarketDataPoint(
                attr(item, "timestamp"),
                decimal(item, "price"),
                decimal(item, "previousClose"),
                decimal(item, "change"),
                decimal(item, "changePercent"),
                longValue(item, "volume"),
                decimal(item, "high52Week"),
                decimal(item, "low52Week"));
    }

    private static String attr(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null ? null : value.s();
    }

    private static BigDecimal decimal(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null || value.n() == null ? null : new BigDecimal(value.n());
    }

    private static Long longValue(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null || value.n() == null ? null : Long.parseLong(value.n());
    }
}
