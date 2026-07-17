package dev.engnotes.query.service;

import dev.engnotes.query.model.DailyMarketDataResponse;
import dev.engnotes.query.model.DailyPoint;
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
    static final int MAX_DAYS = 260;

    private final DynamoDbClient dynamoDb;
    private final String platformTable;

    public DailyMarketDataQuery(
            DynamoDbClient dynamoDb, @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable) {
        this.dynamoDb = dynamoDb;
        this.platformTable = platformTable;
    }

    // Invariant: 260 DAY# items fit one DynamoDB Query page at the projected item size below.
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
                // Everything toDailyPoint reads, and nothing else — crucially NOT `series`, whose
                // intraday points dominate item size. 260 projected rows are a few hundred bytes
                // each and fit one query page, preserving the single-page invariant at the new cap.
                .projectionExpression("#day, SK, #open, #high, #low, #close, previousClose, volume")
                .expressionAttributeNames(Map.of(
                        "#day", "day",
                        "#open", "open",
                        "#high", "high",
                        "#low", "low",
                        "#close", "close"))
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
                DynamoAttributes.dayOf(item),
                DynamoAttributes.decimal(item, "open"),
                DynamoAttributes.decimal(item, "high"),
                DynamoAttributes.decimal(item, "low"),
                DynamoAttributes.decimal(item, "close"),
                DynamoAttributes.decimal(item, "previousClose"),
                DynamoAttributes.longValue(item, "volume"));
    }
}
