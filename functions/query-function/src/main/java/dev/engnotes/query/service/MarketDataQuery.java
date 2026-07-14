package dev.engnotes.query.service;

import dev.engnotes.query.model.MarketDataPoint;
import dev.engnotes.query.model.MarketDataResponse;
import dev.engnotes.query.model.SeriesPoint;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Read-only serving of recent market-data points for a ticker (chart data for the frontend).
 *
 * <p>Design: the ingestion path stores observations with PK=TICKER#{ticker}, SK=TS#{iso8601} and a
 * 24h TTL. "Recent points" is a query on PK=TICKER#{ticker}, SK begins_with TS#, sorted descending,
 * Limit 50, so the response is newest-first and naturally bounded by the TTL window. A second query
 * on SK begins_with DAY#, sorted descending, Limit 1, reads the latest no-TTL DAY# rollup item to
 * enrich the response with the day's intraday price curve. This path never writes, matching the
 * read-only IAM grant on the market-data Lambda.
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

        var pointsRequest = QueryRequest.builder()
                .tableName(platformTable)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.builder().s("TICKER#" + ticker).build(),
                        ":sk", AttributeValue.builder().s("TS#").build()))
                .scanIndexForward(false) // newest timestamp first
                .limit(MAX_POINTS)
                .build();

        List<Map<String, AttributeValue>> items = dynamoDb.query(pointsRequest).items();
        List<MarketDataPoint> points =
                items.stream().map(MarketDataQuery::toPoint).toList();

        var dayRequest = QueryRequest.builder()
                .tableName(platformTable)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.builder().s("TICKER#" + ticker).build(),
                        ":sk", AttributeValue.builder().s("DAY#").build()))
                .scanIndexForward(false) // latest trading day first
                .limit(1)
                .build();

        List<Map<String, AttributeValue>> dayItems = dynamoDb.query(dayRequest).items();

        List<SeriesPoint> daySeries = List.of();
        BigDecimal previousClose = null;
        String day = null;
        if (!dayItems.isEmpty()) {
            Map<String, AttributeValue> dayItem = dayItems.getFirst();
            daySeries = toSeries(dayItem);
            previousClose = decimal(dayItem, "previousClose");
            day = dayOf(dayItem);
        }

        if (points.isEmpty() && dayItems.isEmpty()) {
            log.info("No market data found. ticker={}", ticker);
            return MarketDataResponse.notFound(ticker);
        }

        boolean found = !points.isEmpty() || !daySeries.isEmpty();
        return new MarketDataResponse(ticker, points, found, daySeries, previousClose, day);
    }

    /**
     * The single latest TS# point for a ticker, if one exists. A narrower read than {@link
     * #findRecentPoints}: callers that only need the most recent observation (StoryQuery, spec
     * sub-project C Task 16) would otherwise pay for that method's 50-item points query plus its
     * unrelated DAY# rollup query, both wasted when only the newest point is used.
     */
    public Optional<MarketDataPoint> findLatestPoint(String rawTicker) {
        String ticker = Tickers.validated(rawTicker);

        var pointsRequest = QueryRequest.builder()
                .tableName(platformTable)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.builder().s("TICKER#" + ticker).build(),
                        ":sk", AttributeValue.builder().s("TS#").build()))
                .scanIndexForward(false) // newest timestamp first
                .limit(1)
                .build();

        List<Map<String, AttributeValue>> items = dynamoDb.query(pointsRequest).items();
        return items.isEmpty() ? Optional.empty() : Optional.of(toPoint(items.getFirst()));
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

    private static List<SeriesPoint> toSeries(Map<String, AttributeValue> item) {
        AttributeValue series = item.get("series");
        if (series == null || series.l() == null) {
            return List.of();
        }
        return series.l().stream()
                .map(AttributeValue::m)
                .map(point -> new SeriesPoint(seriesTime(point), seriesPrice(point)))
                .toList();
    }

    private static String seriesTime(Map<String, AttributeValue> point) {
        AttributeValue time = point.get("t");
        return time == null ? null : time.s();
    }

    private static BigDecimal seriesPrice(Map<String, AttributeValue> point) {
        AttributeValue price = point.get("p");
        return price == null || price.n() == null ? null : new BigDecimal(price.n());
    }

    private static String dayOf(Map<String, AttributeValue> item) {
        String day = attr(item, "day");
        if (day != null) {
            return day;
        }
        String sk = attr(item, "SK");
        return sk != null && sk.startsWith("DAY#") ? sk.substring("DAY#".length()) : sk;
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
