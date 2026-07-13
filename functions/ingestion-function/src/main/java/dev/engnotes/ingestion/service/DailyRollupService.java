package dev.engnotes.ingestion.service;

import dev.engnotes.ingestion.model.MarketDataResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Maintains one daily OHLCV rollup item per ticker and IST trading day, so weekly views can
 * outlive the 24h TTL on raw points.
 *
 * <p>Key: {@code PK=TICKER#{ticker}, SK=DAY#{yyyy-MM-dd}} where the date is the Asia/Kolkata
 * calendar date of the observation (NSE trading day). The item carries NO {@code ttl} attribute,
 * mirroring the BASELINE item, so it persists across teardowns of everything but the Data stack.
 *
 * <p>DynamoDB update expressions have no max/min, so this is a read-modify-write: GetItem, merge
 * open (keep first), high/low (extremes), close (last), then PutItem. At the 5-minute ingest
 * cadence per ticker there is no meaningful write contention.
 *
 * <p>Rollups are best-effort: any failure logs WARN and returns, never failing the pipeline's
 * store step.
 */
@Service
public class DailyRollupService {

    private static final Logger log = LoggerFactory.getLogger(DailyRollupService.class);

    static final ZoneId TRADING_ZONE = ZoneId.of("Asia/Kolkata");

    private final DynamoDbClient dynamoDb;
    private final String platformTable;

    public DailyRollupService(
            DynamoDbClient dynamoDb, @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable) {
        this.dynamoDb = dynamoDb;
        this.platformTable = platformTable;
    }

    public void upsert(MarketDataResponse data, Instant observedAt) {
        if (data.price() == null) {
            return;
        }
        try {
            String day = LocalDate.ofInstant(observedAt, TRADING_ZONE).toString();
            Map<String, AttributeValue> key = Map.of(
                    "PK", str("TICKER#" + data.ticker()),
                    "SK", str("DAY#" + day));

            Map<String, AttributeValue> existing = dynamoDb.getItem(GetItemRequest.builder()
                            .tableName(platformTable)
                            .key(key)
                            .consistentRead(true)
                            .build())
                    .item();

            BigDecimal price = data.price();
            BigDecimal open = existing.containsKey("open")
                    ? new BigDecimal(existing.get("open").n())
                    : price;
            BigDecimal high = existing.containsKey("high")
                    ? new BigDecimal(existing.get("high").n()).max(price)
                    : price;
            BigDecimal low = existing.containsKey("low")
                    ? new BigDecimal(existing.get("low").n()).min(price)
                    : price;
            long samples = existing.containsKey("samples")
                    ? Long.parseLong(existing.get("samples").n()) + 1
                    : 1;

            Map<String, AttributeValue> item = new HashMap<>(key);
            item.put("ticker", str(data.ticker()));
            item.put("day", str(day));
            item.put("open", num(open));
            item.put("high", num(high));
            item.put("low", num(low));
            item.put("close", num(price));
            item.put(
                    "samples",
                    AttributeValue.builder().n(Long.toString(samples)).build());
            item.put("updatedAt", str(observedAt.toString()));
            if (data.volume() != null) {
                item.put(
                        "volume",
                        AttributeValue.builder()
                                .n(String.valueOf(data.volume()))
                                .build());
            } else if (existing.containsKey("volume")) {
                item.put("volume", existing.get("volume"));
            }

            dynamoDb.putItem(
                    PutItemRequest.builder().tableName(platformTable).item(item).build());
        } catch (Exception e) {
            log.warn("Daily rollup upsert failed. ticker={} error={}", data.ticker(), e.getMessage());
        }
    }

    private static AttributeValue str(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue num(BigDecimal value) {
        return AttributeValue.builder().n(value.toPlainString()).build();
    }
}
