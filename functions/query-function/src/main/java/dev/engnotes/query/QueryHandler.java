package dev.engnotes.query;

import dev.engnotes.query.model.DailyMarketDataRequest;
import dev.engnotes.query.model.DailyMarketDataResponse;
import dev.engnotes.query.model.InsightFeedRequest;
import dev.engnotes.query.model.InsightFeedResponse;
import dev.engnotes.query.model.MarketDataResponse;
import dev.engnotes.query.model.QueryRequest;
import dev.engnotes.query.model.QueryResponse;
import dev.engnotes.query.model.StoryResponse;
import dev.engnotes.query.service.DailyMarketDataQuery;
import dev.engnotes.query.service.InsightFeedQuery;
import dev.engnotes.query.service.InsightQuery;
import dev.engnotes.query.service.MarketDataQuery;
import dev.engnotes.query.service.StoryQuery;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Query Lambda - Spring Cloud Function entry point (read path).
 *
 * <p>The bean name ("serveInsight") must match SPRING_CLOUD_FUNCTION_DEFINITION in QueryStack. It is
 * deliberately not "queryHandler": component scanning already registers this
 * {@code @SpringBootApplication} class as a bean named "queryHandler", so a same-named {@code @Bean}
 * method collides (BeanDefinitionOverrideException). API Gateway maps {@code GET /insights/{ticker}}
 * to this function via its integration request template (ticker from the path, correlationId from
 * the request id).
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
    public Function<QueryRequest, QueryResponse> serveInsight(InsightQuery insightQuery) {
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

    /** Returns recent stored market-data points for the requested ticker (newest first). */
    @Bean
    public Function<QueryRequest, MarketDataResponse> serveMarketData(MarketDataQuery marketDataQuery) {
        return request -> {
            String ticker = request.ticker();
            String correlationId = request.correlationId();

            log.info("Serving market data. ticker={} correlationId={}", ticker, correlationId);

            MarketDataResponse response = marketDataQuery.findRecentPoints(ticker);

            log.info(
                    "Market data query complete. ticker={} found={} points={} correlationId={}",
                    ticker,
                    response.found(),
                    response.points().size(),
                    correlationId);

            return response;
        };
    }

    /** Returns daily OHLCV rollups for the requested ticker (newest first, capped at 90 days). */
    @Bean
    public Function<DailyMarketDataRequest, DailyMarketDataResponse> serveDailyMarketData(
            DailyMarketDataQuery dailyMarketDataQuery) {
        return request -> {
            String ticker = request.ticker();
            String correlationId = request.correlationId();

            log.info(
                    "Serving daily market data. ticker={} days={} correlationId={}",
                    ticker,
                    request.days(),
                    correlationId);

            DailyMarketDataResponse response = dailyMarketDataQuery.findDailyPoints(ticker, request.days());

            log.info(
                    "Daily market data query complete. ticker={} found={} days={} correlationId={}",
                    ticker,
                    response.found(),
                    response.days().size(),
                    correlationId);

            return response;
        };
    }

    /** Returns the rule-based per-ticker narrative (spec sub-project C, Task 16). */
    @Bean
    public Function<QueryRequest, StoryResponse> serveStory(StoryQuery storyQuery) {
        return request -> {
            String ticker = request.ticker();
            String correlationId = request.correlationId();

            log.info("Serving story. ticker={} correlationId={}", ticker, correlationId);

            StoryResponse response = storyQuery.story(ticker);

            log.info(
                    "Story query complete. ticker={} days={} insightCount={} correlationId={}",
                    ticker,
                    response.inputs().days(),
                    response.inputs().insightCount(),
                    correlationId);

            return response;
        };
    }

    /** Returns the caller's watchlist insight feed: group insights plus ungrouped tickers' latest. */
    @Bean
    public Function<InsightFeedRequest, InsightFeedResponse> serveInsightFeed(InsightFeedQuery insightFeedQuery) {
        return request -> {
            String ownerSub = request.ownerSub();
            String correlationId = request.correlationId();

            log.info("Serving insight feed. owner={} correlationId={}", ownerSub, correlationId);

            InsightFeedResponse response = insightFeedQuery.feed(ownerSub);

            log.info(
                    "Insight feed query complete. owner={} insights={} correlationId={}",
                    ownerSub,
                    response.insights().size(),
                    correlationId);

            return response;
        };
    }
}
