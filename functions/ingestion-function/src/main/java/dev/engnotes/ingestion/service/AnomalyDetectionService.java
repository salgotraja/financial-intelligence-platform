package dev.engnotes.ingestion.service;

import dev.engnotes.ingestion.model.MarketDataResponse;
import dev.engnotes.ingestion.model.RunningStats;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
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
 * <p>Storage: the baseline overloads the single table as {@code PK=TICKER#{ticker}, SK=BASELINE}; it
 * carries no {@code ttl} so it is not auto-expired. The z-score is computed against the prior
 * baseline before the new point is folded in, so a point is judged against history, not itself.
 *
 * <p>Resilience: evaluation is best-effort. Any failure reading or writing the baseline logs and
 * leaves the response flagged as non-anomalous, so a DynamoDB blip never spends Bedrock and never
 * fails ingestion. The anomaly verdict is judged once against the baseline read at the start of
 * {@link #evaluate}; persisting the folded-in observation is optimistically locked on a numeric
 * {@code version} attribute (conditional put, {@code attribute_not_exists(version) OR version =
 * :expected}) so concurrent Distributed Map fan-out for the same ticker cannot lose an update. A
 * losing writer re-reads the committed baseline, refolds the same observation, and retries with
 * jittered backoff, up to {@link #MAX_WRITE_ATTEMPTS} attempts; if every attempt loses the race the
 * sample is dropped and a warning is logged - a lost baseline sample is acceptable, a corrupted
 * baseline is not.
 */
@Service
public class AnomalyDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionService.class);

    private static final String BASELINE_SK = "BASELINE";
    private static final int MAX_WRITE_ATTEMPTS = 3;
    private static final long BACKOFF_BASE_MILLIS = 20L;
    private static final long BACKOFF_JITTER_MILLIS = 20L;

    private final DynamoDbClient dynamoDb;
    private final String platformTable;
    private final double zThreshold;
    private final int minSamples;

    public AnomalyDetectionService(
            DynamoDbClient dynamoDb,
            @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable,
            @Value("${ANOMALY_Z_THRESHOLD:3.0}") double zThreshold,
            @Value("${ANOMALY_MIN_SAMPLES:5}") int minSamples) {
        this.dynamoDb = dynamoDb;
        this.platformTable = platformTable;
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

            long returnCount = persistBaselineWithRetry(ticker, baseline, returnObs, volumeObs, correlationId);

            log.info(
                    "Anomaly evaluation. ticker={} anomaly={} reason={} returnZ={} volumeZ={} returnCount={} correlationId={}",
                    ticker,
                    anomaly,
                    reason,
                    returnZ,
                    volumeZ,
                    returnCount,
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
                .tableName(platformTable)
                .key(Map.of("PK", str("TICKER#" + ticker), "SK", str(BASELINE_SK)))
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

    /**
     * Folds the observations into the baseline and persists them under optimistic locking, retrying
     * on a lost race by re-reading the committed baseline and refolding the same observations. Gives
     * up silently (after logging a warning) once {@link #MAX_WRITE_ATTEMPTS} is exhausted; the
     * anomaly verdict for this point was already decided against the baseline read in {@link
     * #evaluate}, so a dropped sample here only means the baseline warms up one point slower.
     */
    /**
     * Persists the folded-in baseline and returns its returnCount for the caller's observability log
     * - on a give-up (race lost {@link #MAX_WRITE_ATTEMPTS} times), returns the last computed,
     * unpersisted candidate count instead, since the caller logs unconditionally either way.
     */
    private long persistBaselineWithRetry(
            String ticker,
            Map<String, AttributeValue> initialBaseline,
            Double returnObs,
            Double volumeObs,
            String correlationId) {
        Map<String, AttributeValue> baseline = initialBaseline;
        long returnCount = readStats(baseline, "return").count();

        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            RunningStats returnStats = readStats(baseline, "return");
            RunningStats volumeStats = readStats(baseline, "volume");
            RunningStats updatedReturn = returnObs == null ? returnStats : returnStats.accept(returnObs);
            RunningStats updatedVolume = volumeObs == null ? volumeStats : volumeStats.accept(volumeObs);
            long expectedVersion = readVersion(baseline);
            returnCount = updatedReturn.count();

            try {
                writeBaseline(ticker, updatedReturn, updatedVolume, expectedVersion);
                return returnCount;
            } catch (ConditionalCheckFailedException e) {
                if (attempt == MAX_WRITE_ATTEMPTS) {
                    log.warn(
                            "Baseline update lost the optimistic-lock race after {} attempts; skipping this sample. ticker={} correlationId={}",
                            MAX_WRITE_ATTEMPTS,
                            ticker,
                            correlationId);
                    return returnCount;
                }
                sleepWithJitter(attempt);
                baseline = readBaseline(ticker);
            }
        }
        return returnCount;
    }

    private void writeBaseline(
            String ticker, RunningStats returnStats, RunningStats volumeStats, long expectedVersion) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", str("TICKER#" + ticker));
        item.put("SK", str(BASELINE_SK));
        item.put("returnCount", num(returnStats.count()));
        item.put("returnMean", num(returnStats.mean()));
        item.put("returnM2", num(returnStats.m2()));
        item.put("volumeCount", num(volumeStats.count()));
        item.put("volumeMean", num(volumeStats.mean()));
        item.put("volumeM2", num(volumeStats.m2()));
        item.put("version", num(expectedVersion + 1));
        item.put("updatedAt", str(Instant.now().toString()));

        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(platformTable)
                .item(item)
                // Optimistic lock: first write ever (no item, so no version attribute) or the
                // version we read still matches what is committed. A concurrent winner bumps the
                // version first and this check fails with ConditionalCheckFailedException.
                .conditionExpression("attribute_not_exists(version) OR version = :expected")
                .expressionAttributeValues(Map.of(":expected", num(expectedVersion)))
                .build());
    }

    private long readVersion(Map<String, AttributeValue> baseline) {
        return (long) readNumber(baseline, "version");
    }

    private void sleepWithJitter(int attempt) {
        try {
            long delay =
                    BACKOFF_BASE_MILLIS * attempt + ThreadLocalRandom.current().nextLong(BACKOFF_JITTER_MILLIS);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            // Restore the flag and let the retry loop continue; there is no cancellation path to honor here.
            Thread.currentThread().interrupt();
        }
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
