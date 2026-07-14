package dev.engnotes.insight;

import dev.engnotes.insight.model.CorrelationRequest;
import dev.engnotes.insight.model.CorrelationResponse;
import dev.engnotes.insight.model.InsightRequest;
import dev.engnotes.insight.model.InsightResponse;
import dev.engnotes.insight.service.CorrelationService;
import dev.engnotes.insight.service.InsightGenerationService;
import dev.engnotes.insight.service.MarketHours;
import java.time.Clock;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Insight Lambda - Spring Cloud Function entry point.
 *
 * <p>Two beans share this jar, selected per-Lambda by SPRING_CLOUD_FUNCTION_DEFINITION (CDK
 * IngestionStack): "generateInsight" for the Bedrock insight (per-ticker, or cross-ticker when the
 * ticker belongs to a correlation group - Task 7), and "computeCorrelations" for the scheduled
 * cross-ticker correlation pass (spec section 7).
 *
 * <p>generateInsight: the GenerateInsight state passes this function the FetchMarketData output (a
 * MarketDataResponse), and its output is the pipeline's final payload before ExecutionSucceeded.
 * Runs in the VPC so the Bedrock call goes over the PrivateLink interface endpoint (FoundationStack
 * BedrockRuntimeEndpoint), not the public internet.
 */
@SpringBootApplication
public class InsightHandler {

    private static final Logger log = LoggerFactory.getLogger(InsightHandler.class);

    public static void main(String[] args) {
        SpringApplication.run(InsightHandler.class, args);
    }

    /**
     * Generates an insight for the ticker's market data and returns it as the pipeline's output.
     * Delegates entirely to {@link InsightGenerationService}, which resolves the ticker's
     * correlation group (Task 7) and either runs the unchanged per-ticker Bedrock path or generates
     * one cross-ticker insight for the whole group, storing it for every member.
     */
    @Bean
    public Function<InsightRequest, InsightResponse> generateInsight(
            InsightGenerationService insightGenerationService) {
        return insightGenerationService::generate;
    }

    /**
     * Computes pairwise return correlations across the WATCHSET and persists threshold-clustered
     * groups (spec section 7). EventBridge triggers this directly (no Step Functions state machine),
     * every 15 minutes inside the market-hours cron envelope; the cron cannot express holidays, so
     * this guard mirrors fetchMarketData's scheduled no-op.
     */
    @Bean
    public Function<CorrelationRequest, CorrelationResponse> computeCorrelations(
            CorrelationService correlationService, Clock clock) {
        return request -> {
            if (!MarketHours.isMarketOpen(clock.instant())) {
                log.info("Market closed: skipping correlation pass. source={}", request.source());
                return CorrelationResponse.marketClosed();
            }

            log.info("Starting correlation pass. source={}", request.source());
            CorrelationResponse response = correlationService.compute(clock.instant());
            log.info(
                    "Correlation pass complete. tickersEvaluated={} groupsComputed={}",
                    response.tickersEvaluated(),
                    response.groupsComputed());

            return response;
        };
    }
}
