package dev.engnotes.ingestion.service;

import dev.engnotes.ingestion.model.DailyBar;
import dev.engnotes.ingestion.provider.YahooHistoryProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * One-time-per-ticker historical backfill: a year of daily bars written as ordinary DAY# items so
 * the multi-horizon analysis has history on day one. Every put is conditional on the item NOT
 * existing — live rollups (richer: samples, series, intraday extremes) always win, and reruns
 * are cheap gap-fill no-ops. Backfilled items carry {@code backfilled=true} for provenance and,
 * like live rollups, no TTL.
 */
@Service
public class HistoryBackfillService {

    private static final Logger log = LoggerFactory.getLogger(HistoryBackfillService.class);

    /** Outcome of one ticker's backfill: bars written vs skipped because a rollup already existed. */
    public record BackfillResult(String ticker, int written, int skipped) {}

    private final DynamoDbClient dynamoDb;
    private final YahooHistoryProvider historyProvider;

    @Value("${PLATFORM_TABLE:financial-platform-dev}")
    private String platformTable;

    public HistoryBackfillService(DynamoDbClient dynamoDb, YahooHistoryProvider historyProvider) {
        this.dynamoDb = dynamoDb;
        this.historyProvider = historyProvider;
    }

    public BackfillResult backfill(String ticker, String correlationId) {
        List<DailyBar> bars = historyProvider.fetchDailyBars(ticker, correlationId);
        int written = 0;
        int skipped = 0;
        for (DailyBar bar : bars) {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("PK", str("TICKER#" + ticker));
            item.put("SK", str("DAY#" + bar.date()));
            item.put("ticker", str(ticker));
            item.put("day", str(bar.date().toString()));
            putNumber(item, "open", bar.open());
            putNumber(item, "high", bar.high());
            putNumber(item, "low", bar.low());
            putNumber(item, "close", bar.close());
            // Intentionally omit previousClose: readers compute close-to-close returns; the
            // notable-day story sentence skips rows without it.
            if (bar.volume() != null) {
                item.put(
                        "volume",
                        AttributeValue.builder().n(Long.toString(bar.volume())).build());
            }
            item.put("updatedAt", str(Instant.now().toString()));
            item.put("backfilled", AttributeValue.builder().bool(true).build());
            try {
                dynamoDb.putItem(PutItemRequest.builder()
                        .tableName(platformTable)
                        .item(item)
                        .conditionExpression("attribute_not_exists(PK) AND attribute_not_exists(SK)")
                        .build());
                written++;
            } catch (ConditionalCheckFailedException e) {
                skipped++; // a live rollup (or an earlier backfill) already owns this day
            }
        }
        log.info(
                "History backfill complete. ticker={} bars={} written={} skipped={} correlationId={}",
                ticker,
                bars.size(),
                written,
                skipped,
                correlationId);
        return new BackfillResult(ticker, written, skipped);
    }

    private static void putNumber(Map<String, AttributeValue> item, String name, BigDecimal value) {
        if (value != null) {
            item.put(name, AttributeValue.builder().n(value.toPlainString()).build());
        }
    }

    private static AttributeValue str(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
