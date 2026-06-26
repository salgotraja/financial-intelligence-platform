package dev.engnotes.insight;

import dev.engnotes.insight.model.InsightRequest;
import dev.engnotes.insight.model.InsightResponse;
import dev.engnotes.insight.service.BedrockInsightService;
import dev.engnotes.insight.service.InsightStoreService;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Insight Lambda - Spring Cloud Function entry point.
 *
 * <p>The bean name ("generateInsight") must match SPRING_CLOUD_FUNCTION_DEFINITION in the
 * CDK stack. The GenerateInsight state passes this function the FetchMarketData output (a
 * MarketDataResponse), and its output is the pipeline's final payload before ExecutionSucceeded.
 *
 * <p>Runs in the VPC so the Bedrock call goes over the PrivateLink interface endpoint
 * (FoundationStack BedrockRuntimeEndpoint), not the public internet.
 */
@SpringBootApplication
public class InsightHandler {

    private static final Logger log = LoggerFactory.getLogger(InsightHandler.class);

    public static void main(String[] args) {
        SpringApplication.run(InsightHandler.class, args);
    }

    /**
     * Generates a Bedrock insight for the ticker's market data, stores it in DynamoDB, and
     * returns it as the pipeline's output.
     */
    @Bean
    public Function<InsightRequest, InsightResponse> generateInsight(
            BedrockInsightService insightService, InsightStoreService storeService) {
        return request -> {
            String ticker = request.getTicker();
            String correlationId = request.getCorrelationId();

            log.info("Starting insight generation. ticker={} correlationId={}", ticker, correlationId);

            InsightResponse insight = insightService.generate(request);
            storeService.store(insight);

            log.info("Insight generation complete. ticker={} correlationId={}", ticker, correlationId);

            return insight;
        };
    }
}
