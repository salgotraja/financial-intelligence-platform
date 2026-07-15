package dev.engnotes.insight.service;

import dev.engnotes.insight.model.TickerSeries;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Read path for correlation inputs: the WATCHSET ticker union and each ticker's recent priced
 * points, bucketed to the minute.
 *
 * <p>Bucketing to the minute (not the ~5-minute ingest cadence) corrects for cross-ticker fetch-time
 * jitter within one fan-out execution (the Distributed Map's bounded concurrency means tickers are
 * not fetched at the exact same instant) without coarsening the series in prod, where the schedule
 * polls every minute. It mirrors DailyRollupService's own minute-level series bucketing.
 */
@Service
public class CorrelationDataReader {

    private static final String TICKER_PREFIX = "TICKER#";
    private static final String TS_PREFIX = "TS#";

    private final DynamoDbClient dynamoDb;
    private final String platformTable;

    public CorrelationDataReader(
            DynamoDbClient dynamoDb, @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable) {
        this.dynamoDb = dynamoDb;
        this.platformTable = platformTable;
    }

    /** The distinct-ticker union (PK=WATCHSET, SK begins_with TICKER#). Paginates. */
    public List<String> watchsetTickers() {
        QueryRequest request = QueryRequest.builder()
                .tableName(platformTable)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                .expressionAttributeValues(
                        Map.of(":pk", AttributeValues.s("WATCHSET"), ":sk", AttributeValues.s(TICKER_PREFIX)))
                .build();
        return dynamoDb.queryPaginator(request).items().stream()
                .map(item -> item.get("ticker"))
                .filter(Objects::nonNull)
                .map(AttributeValue::s)
                .toList();
    }

    /**
     * Reads up to {@code limit} of a ticker's most recent priced TS# points, bucketed to the minute
     * and returned in ascending chronological order. Points with no price, a non-positive price
     * (invalid market data), or no timestamp are skipped. When two points fall in the same bucket,
     * the most recent one wins (the query is newest-first, so the first point seen per bucket is
     * kept).
     */
    public TickerSeries readSeries(String ticker, int limit) {
        QueryRequest request = QueryRequest.builder()
                .tableName(platformTable)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                .expressionAttributeValues(
                        Map.of(":pk", AttributeValues.s(TICKER_PREFIX + ticker), ":sk", AttributeValues.s(TS_PREFIX)))
                .scanIndexForward(false)
                .limit(limit)
                .build();

        LinkedHashMap<String, BigDecimal> priceByBucket = new LinkedHashMap<>();
        for (Map<String, AttributeValue> item : dynamoDb.query(request).items()) {
            BigDecimal price = price(item);
            String timestamp = attr(item, "timestamp");
            if (price == null || price.signum() <= 0 || timestamp == null) {
                continue;
            }
            String bucket =
                    Instant.parse(timestamp).truncatedTo(ChronoUnit.MINUTES).toString();
            priceByBucket.putIfAbsent(bucket, price);
        }

        List<String> ascendingBuckets = priceByBucket.keySet().stream().sorted().toList();
        List<BigDecimal> ascendingPrices =
                ascendingBuckets.stream().map(priceByBucket::get).toList();
        return new TickerSeries(ticker, ascendingBuckets, ascendingPrices);
    }

    private static BigDecimal price(Map<String, AttributeValue> item) {
        AttributeValue value = item.get("price");
        return value == null || value.n() == null ? null : new BigDecimal(value.n());
    }

    private static String attr(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null ? null : value.s();
    }
}
