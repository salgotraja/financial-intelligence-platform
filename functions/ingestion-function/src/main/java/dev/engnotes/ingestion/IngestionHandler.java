package dev.engnotes.ingestion;

import dev.engnotes.ingestion.model.MarketDataRequest;
import dev.engnotes.ingestion.model.MarketDataResponse;
import dev.engnotes.ingestion.service.AnomalyDetectionService;
import dev.engnotes.ingestion.service.HistoryBackfillService;
import dev.engnotes.ingestion.service.MarketDataFetchService;
import dev.engnotes.ingestion.service.MarketDataStoreService;
import dev.engnotes.ingestion.service.MarketHours;
import dev.engnotes.ingestion.validation.TickerValidator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Ingestion Lambda - Spring Cloud Function entry point.
 *
 * <p>Spring Cloud Function maps a Java Function&lt;I, O&gt; to a Lambda handler. The bean name
 * ("fetchMarketData") must match SPRING_CLOUD_FUNCTION_DEFINITION in the CDK stack.
 *
 * <p>SnapStart: the Spring context is initialised once during snapshot creation; subsequent
 * invocations restore from the snapshot, so cold start is effectively eliminated.
 *
 * <p>Correlation IDs: every log line carries the correlation id derived from the Step Functions
 * execution id, so a single CloudWatch Logs Insights filter shows the full request flow.
 */
@SpringBootApplication
public class IngestionHandler {

    private static final Logger log = LoggerFactory.getLogger(IngestionHandler.class);

    /** Matches the "source" the EventBridge rules put on the state machine input (IngestionStack). */
    private static final String SCHEDULED_SOURCE = "eventbridge-schedule";

    public static void main(String[] args) {
        SpringApplication.run(IngestionHandler.class, args);
    }

    /**
     * Fetches live market data for a ticker, evaluates it against the rolling baseline to flag
     * anomalies (the Bedrock gate, spec section 6), then stores it in DynamoDB (hot) and S3 (cold).
     * The Step Functions Choice routes to insight generation only when {@code anomaly} is true.
     *
     * <p>Scheduled runs ({@code source=eventbridge-schedule}) no-op when the market is closed
     * (weekend, NSE holiday, or outside the session window): the two EventBridge rules already
     * cron-gate weekdays and hours, so this only ever trips on a holiday or a session edge, and it
     * burns one trivial invocation per rule fire instead of a provider call and a write. On-demand
     * runs ({@code source=on-demand}, from {@code POST /ingest/{ticker}}) are exempt and always fetch.
     */
    @Bean
    public Function<MarketDataRequest, MarketDataResponse> fetchMarketData(
            MarketDataFetchService fetchService,
            AnomalyDetectionService anomalyService,
            MarketDataStoreService storeService,
            Clock clock) {
        return request -> {
            String correlationId = request.correlationId();
            // Validate at the trust boundary (spec section 12) before the ticker reaches the
            // provider URL, S3 keys/tags, or DynamoDB writes downstream.
            String ticker = TickerValidator.validate(request.ticker());

            if (SCHEDULED_SOURCE.equals(request.source()) && !MarketHours.isMarketOpen(clock.instant())) {
                log.info("Market closed: skipping scheduled fetch. ticker={} correlationId={}", ticker, correlationId);
                return MarketDataResponse.builder()
                        .ticker(ticker)
                        .correlationId(correlationId)
                        .dataSource("market-closed")
                        .stored(false)
                        .anomaly(false)
                        .build();
            }

            log.info("Starting market data fetch. ticker={} correlationId={}", ticker, correlationId);

            MarketDataResponse marketData = fetchService.fetch(ticker, correlationId);
            marketData = anomalyService.evaluate(marketData, correlationId);

            // On-demand refreshes (POST /ingest/{ticker}) must always generate an insight,
            // regardless of the anomaly gate's verdict; scheduled runs stay anomaly-gated.
            if ("on-demand".equals(request.source()) && !marketData.anomaly()) {
                log.info(
                        "On-demand refresh: overriding anomaly gate to force insight generation. ticker={} correlationId={}",
                        ticker,
                        correlationId);
                marketData = marketData.withAnomaly(true, "on-demand refresh");
            }

            marketData = storeService.store(marketData, correlationId);

            log.info(
                    "Market data fetch complete. ticker={} price={} anomaly={} correlationId={}",
                    ticker,
                    marketData.price(),
                    marketData.anomaly(),
                    correlationId);

            return marketData;
        };
    }

    /**
     * Watchlist-add backfill: consumes filtered platform-table stream batches (INSERTs with
     * PK=WATCHSET, filtered by the event-source mapping in IngestionStack) and fills a year of
     * DAY# history for each newly watched ticker. Untyped map in/out, like the other stream and
     * Cognito consumers: the event shape is AWS's, not ours. Per-record failures are logged and
     * every ticker in the batch is still attempted, but if any ticker failed the bean rethrows
     * after the loop so the mapping's bounded retries (IngestionStack retryAttempts(2)) actually
     * retry the batch: the backfill is idempotent (conditional puts), so retrying an
     * already-succeeded ticker is just a fetch-and-skip no-op.
     */
    @Bean
    public Function<Map<String, Object>, Map<String, Object>> backfillDailyHistory(
            HistoryBackfillService backfillService) {
        return event -> {
            int processed = 0;
            int written = 0;
            int skipped = 0;
            List<String> failedTickers = new ArrayList<>();
            for (Object recordObj : records(event)) {
                String ticker = newImageTicker(recordObj);
                if (ticker == null || ticker.isBlank()) {
                    continue;
                }
                processed++;
                try {
                    var result = backfillService.backfill(TickerValidator.validate(ticker), "stream-backfill");
                    written += result.written();
                    skipped += result.skipped();
                } catch (RuntimeException e) {
                    log.error(
                            "History backfill failed for one ticker, continuing. ticker={} error={}",
                            sanitizeForLog(ticker),
                            e.toString());
                    failedTickers.add(sanitizeForLog(ticker));
                }
            }
            if (!failedTickers.isEmpty()) {
                throw new RuntimeException(
                        "Backfill batch had %d failed ticker(s): %s".formatted(failedTickers.size(), failedTickers));
            }
            log.info("Backfill batch complete. processed={} written={} skipped={}", processed, written, skipped);
            return Map.of("processed", processed, "written", written, "skipped", skipped);
        };
    }

    private static String sanitizeForLog(String ticker) {
        return ticker.replaceAll("[^A-Z0-9.^-]", "_");
    }

    private static List<?> records(Map<String, Object> event) {
        return event.get("Records") instanceof List<?> records ? records : List.of();
    }

    private static String newImageTicker(Object recordObj) {
        return recordObj instanceof Map<?, ?> streamRecord
                        && streamRecord.get("dynamodb") instanceof Map<?, ?> dynamodb
                        && dynamodb.get("NewImage") instanceof Map<?, ?> newImage
                        && newImage.get("ticker") instanceof Map<?, ?> ticker
                        && ticker.get("S") instanceof String value
                ? value
                : null;
    }
}
