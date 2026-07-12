package dev.engnotes.ingestion;

import dev.engnotes.ingestion.model.MarketDataRequest;
import dev.engnotes.ingestion.model.MarketDataResponse;
import dev.engnotes.ingestion.service.AnomalyDetectionService;
import dev.engnotes.ingestion.service.MarketDataFetchService;
import dev.engnotes.ingestion.service.MarketDataStoreService;
import dev.engnotes.ingestion.validation.TickerValidator;
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

    public static void main(String[] args) {
        SpringApplication.run(IngestionHandler.class, args);
    }

    /**
     * Fetches live market data for a ticker, evaluates it against the rolling baseline to flag
     * anomalies (the Bedrock gate, spec section 6), then stores it in DynamoDB (hot) and S3 (cold).
     * The Step Functions Choice routes to insight generation only when {@code anomaly} is true.
     */
    @Bean
    public Function<MarketDataRequest, MarketDataResponse> fetchMarketData(
            MarketDataFetchService fetchService,
            AnomalyDetectionService anomalyService,
            MarketDataStoreService storeService) {
        return request -> {
            String correlationId = request.correlationId();
            // Validate at the trust boundary (spec section 12) before the ticker reaches the
            // provider URL, S3 keys/tags, or DynamoDB writes downstream.
            String ticker = TickerValidator.validate(request.ticker());

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
}
