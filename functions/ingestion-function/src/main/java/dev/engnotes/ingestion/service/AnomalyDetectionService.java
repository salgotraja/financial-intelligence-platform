package dev.engnotes.ingestion.service;

import dev.engnotes.ingestion.model.MarketDataResponse;
import dev.engnotes.ingestion.model.RunningStats;
import java.math.BigDecimal;
import java.time.Instant;
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
 * Anomaly gate for Bedrock (spec section 6).
 *
 * <p>Insighting every ingest is expensive and noisy, so Bedrock is anomaly-gated. This service
 * keeps a per-ticker rolling baseline (Welford running mean/variance of the percent return and of
 * volume) in the {@code BASELINE} item, and flags an anomaly when the new point's z-score crosses
 * {@code k} on either signal, or when price breaks the 52-week range. The flag is written onto the
 * {@link MarketDataResponse} so the Step Functions Choice can route to insight generation or skip it.
 *
 * <p>Storage: the baseline overloads the market-data table as {@code PK=ticker, SK=BASELINE}; it
 * carries no {@code ttl} so it is not auto-expired. The z-score is computed against the prior
 * baseline before the new point is folded in, so a point is judged against history, not itself.
 *
 * <p>Resilience: evaluation is best-effort. Any failure reading or writing the baseline logs and
 * leaves the response flagged as non-anomalous, so a DynamoDB blip never spends Bedrock and never
 * fails ingestion. Read-modify-write is safe for the single-ticker skeleton; concurrent ingests of
 * the same ticker would need an atomic conditional update (future hardening).
 */
@Service
public class AnomalyDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionService.class);

    private static final String BASELINE_SK = "BASELINE";

    private final DynamoDbClient dynamoDb;
    private final String marketDataTable;
    private final double zThreshold;
    private final int minSamples;

    public AnomalyDetectionService(
            DynamoDbClient dynamoDb,
            @Value("${MARKET_DATA_TABLE:financial-market-data-dev}") String marketDataTable,
            @Value("${ANOMALY_Z_THRESHOLD:3.0}") double zThreshold,
            @Value("${ANOMALY_MIN_SAMPLES:5}") int minSamples) {
        this.dynamoDb = dynamoDb;
        this.marketDataTable = marketDataTable;
        this.zThreshold = zThreshold;
        this.minSamples = minSamples;
    }

    /**
     * Evaluates the point against the ticker's baseline, updates the baseline, and returns a copy of
     * {@code data} carrying the anomaly verdict.
     */
    public MarketDataResponse evaluate(MarketDataResponse data, String correlationId) {
        String ticker = data.ticker();
        try {
            Map<String, AttributeValue> baseline = readBaseline(ticker);
            RunningStats returnStats = readStats(baseline, "return");
            RunningStats volumeStats = readStats(baseline, "volume");

            Double returnObs = toDouble(data.changePercent());
            Double volumeObs = data.volume() == null ? null : data.volume().doubleValue();

            double returnZ = warmZScore(returnStats, returnObs);
            double volumeZ = warmZScore(volumeStats, volumeObs);

            boolean returnAnomaly = Math.abs(returnZ) >= zThreshold;
            boolean volumeAnomaly = Math.abs(volumeZ) >= zThreshold;
            boolean weekBreak = fiftyTwoWeekBreak(data);

            boolean anomaly = returnAnomaly || volumeAnomaly || weekBreak;
            String reason = anomaly ? buildReason(returnAnomaly, returnZ, volumeAnomaly, volumeZ, weekBreak) : null;

            // Fold the new observations into the baseline, then persist.
            RunningStats updatedReturn = returnObs == null ? returnStats : returnStats.accept(returnObs);
            RunningStats updatedVolume = volumeObs == null ? volumeStats : volumeStats.accept(volumeObs);
            writeBaseline(ticker, updatedReturn, updatedVolume);

            log.info(
                    "Anomaly evaluation. ticker={} anomaly={} reason={} returnZ={} volumeZ={} returnCount={} correlationId={}",
                    ticker,
                    anomaly,
                    reason,
                    returnZ,
                    volumeZ,
                    updatedReturn.count(),
                    correlationId);

            return data.withAnomaly(anomaly, reason);

        } catch (Exception e) {
            // Best-effort: never let the gate fail ingestion or spend Bedrock on a degraded path.
            log.error(
                    "Anomaly evaluation failed, defaulting to no-insight. ticker={} correlationId={} error={}",
                    ticker,
                    correlationId,
                    e.getMessage(),
                    e);
            return data.withAnomaly(false, null);
        }
    }

    /** Z-score only once the baseline has enough samples; otherwise 0 (warmup, no flag). */
    private double warmZScore(RunningStats stats, Double observation) {
        if (observation == null || stats.count() < minSamples) {
            return 0.0;
        }
        return stats.zScore(observation);
    }

    private boolean fiftyTwoWeekBreak(MarketDataResponse data) {
        BigDecimal price = data.price();
        if (price == null) {
            return false;
        }
        boolean aboveHigh = data.high52Week() != null && price.compareTo(data.high52Week()) > 0;
        boolean belowLow = data.low52Week() != null && price.compareTo(data.low52Week()) < 0;
        return aboveHigh || belowLow;
    }

    private String buildReason(
            boolean returnAnomaly, double returnZ, boolean volumeAnomaly, double volumeZ, boolean weekBreak) {
        StringBuilder reason = new StringBuilder();
        if (returnAnomaly) {
            reason.append(String.format("return z=%.2f; ", returnZ));
        }
        if (volumeAnomaly) {
            reason.append(String.format("volume z=%.2f; ", volumeZ));
        }
        if (weekBreak) {
            reason.append("52-week break; ");
        }
        return reason.toString().strip();
    }

    private Map<String, AttributeValue> readBaseline(String ticker) {
        var response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(marketDataTable)
                .key(Map.of("ticker", str(ticker), "timestamp", str(BASELINE_SK)))
                .consistentRead(true)
                .build());
        return response.hasItem() ? response.item() : Map.of();
    }

    private RunningStats readStats(Map<String, AttributeValue> baseline, String prefix) {
        if (baseline.isEmpty()) {
            return RunningStats.empty();
        }
        long count = (long) readNumber(baseline, prefix + "Count");
        double mean = readNumber(baseline, prefix + "Mean");
        double m2 = readNumber(baseline, prefix + "M2");
        return new RunningStats(count, mean, m2);
    }

    private void writeBaseline(String ticker, RunningStats returnStats, RunningStats volumeStats) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("ticker", str(ticker));
        item.put("timestamp", str(BASELINE_SK));
        item.put("returnCount", num(returnStats.count()));
        item.put("returnMean", num(returnStats.mean()));
        item.put("returnM2", num(returnStats.m2()));
        item.put("volumeCount", num(volumeStats.count()));
        item.put("volumeMean", num(volumeStats.mean()));
        item.put("volumeM2", num(volumeStats.m2()));
        item.put("updatedAt", str(Instant.now().toString()));

        dynamoDb.putItem(
                PutItemRequest.builder().tableName(marketDataTable).item(item).build());
    }

    private double readNumber(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null || value.n() == null ? 0.0 : Double.parseDouble(value.n());
    }

    private Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private AttributeValue str(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private AttributeValue num(double value) {
        return AttributeValue.builder().n(Double.toString(value)).build();
    }

    private AttributeValue num(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
