package dev.engnotes.query.service;

import dev.engnotes.query.model.DailyMarketDataResponse;
import dev.engnotes.query.model.DailyPoint;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Read-only serving of daily OHLCV rollups for a ticker (weekly/period chart data for the
 * frontend, spec sub-project B).
 *
 * <p>Design: PK=TICKER#{ticker}, SK begins_with DAY# on the platform table, sorted descending
 * (newest trading day first), Limit={@code days}. {@code days} is caller-controlled but capped at
 * {@link #MAX_DAYS}, so a single bounded {@code Query} with a {@code Limit} is correct here - no
 * paginator is needed, unlike an unbounded scan. This path never writes, matching the read-only
 * IAM grant on the market-data Lambda.
 *
 * <p>The ticker is validated against the same strict allowlist as the sibling market-data route
 * before it reaches the DynamoDB key expression; the exception message must keep the "Invalid
 * ticker" prefix that QueryStack's 400 selection pattern matches. An invalid or out-of-range {@code
 * days} value is never a client error: it silently defaults to {@link #DEFAULT_DAYS} or clamps into
 * [{@value #MIN_DAYS}, {@value #MAX_DAYS}], since no phrase for it exists in QueryStack's
 * CLIENT_ERROR_PATTERN and adding one there would touch a constant shared, by comment convention,
 * across independent Maven modules for a parameter that has a well-defined default.
 */
@Service
public class DailyMarketDataQuery {

    private static final Logger log = LoggerFactory.getLogger(DailyMarketDataQuery.class);

    static final int DEFAULT_DAYS = 30;
    static final int MIN_DAYS = 1;
    static final int MAX_DAYS = 90;

    private final DynamoDbClient dynamoDb;
    private final String platformTable;

    public DailyMarketDataQuery(
            DynamoDbClient dynamoDb, @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable) {
        this.dynamoDb = dynamoDb;
        this.platformTable = platformTable;
    }

    // Invariant: 90 DAY# items fit one DynamoDB Query page at current item sizes; item growth past
    // ~11KB average would silently truncate results below the requested days.
    public DailyMarketDataResponse findDailyPoints(String rawTicker, String rawDays) {
        String ticker = Tickers.validated(rawTicker);
        int days = parseDays(rawDays);

        var request = QueryRequest.builder()
                .tableName(platformTable)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.builder().s("TICKER#" + ticker).build(),
                        ":sk", AttributeValue.builder().s("DAY#").build()))
                .scanIndexForward(false) // newest trading day first
                .limit(days)
                .build();

        List<Map<String, AttributeValue>> items = dynamoDb.query(request).items();
        if (items.isEmpty()) {
            log.info("No daily market data found. ticker={}", ticker);
            return DailyMarketDataResponse.notFound(ticker);
        }

        List<DailyPoint> points =
                items.stream().map(DailyMarketDataQuery::toDailyPoint).toList();
        return new DailyMarketDataResponse(ticker, points, true);
    }

    static int parseDays(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_DAYS;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_DAYS;
        }
        return Math.max(MIN_DAYS, Math.min(MAX_DAYS, parsed));
    }

    private static DailyPoint toDailyPoint(Map<String, AttributeValue> item) {
        return new DailyPoint(
                dayOf(item),
                decimal(item, "open"),
                decimal(item, "high"),
                decimal(item, "low"),
                decimal(item, "close"),
                decimal(item, "previousClose"),
                longValue(item, "volume"));
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
