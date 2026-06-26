package dev.engnotes.query;

import dev.engnotes.query.model.QueryRequest;
import dev.engnotes.query.model.QueryResponse;
import dev.engnotes.query.service.InsightQuery;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Query Lambda - Spring Cloud Function entry point (read path).
 *
 * <p>The bean name ("queryHandler") must match SPRING_CLOUD_FUNCTION_DEFINITION in QueryStack.
 * API Gateway maps {@code GET /insights/{ticker}} to this function via its integration request
 * template (ticker from the path, correlationId from the request id).
 *
 * <p>Read-only and Bedrock-free: it serves the latest cached insight straight from DynamoDB, which
 * keeps the read path's p99 low and decoupled from the write path (CQRS, spec section 10).
 */
@SpringBootApplication
public class QueryHandler {

    private static final Logger log = LoggerFactory.getLogger(QueryHandler.class);

    public static void main(String[] args) {
        SpringApplication.run(QueryHandler.class, args);
    }

    /** Returns the latest stored insight for the requested ticker. */
    @Bean
    public Function<QueryRequest, QueryResponse> queryHandler(InsightQuery insightQuery) {
        return request -> {
            String ticker = request.ticker();
            String correlationId = request.correlationId();

            log.info("Serving latest insight. ticker={} correlationId={}", ticker, correlationId);

            QueryResponse response = insightQuery.findLatestInsight(ticker);

            log.info(
                    "Insight query complete. ticker={} found={} correlationId={}",
                    ticker,
                    response.found(),
                    correlationId);

            return response;
        };
    }
}
