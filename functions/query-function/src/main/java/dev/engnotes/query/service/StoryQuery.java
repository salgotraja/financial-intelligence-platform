package dev.engnotes.query.service;

import dev.engnotes.query.model.DeepAnalysisResponse;
import dev.engnotes.query.model.FeedInsight;
import dev.engnotes.query.model.MarketDataPoint;
import dev.engnotes.query.model.StoryInputs;
import dev.engnotes.query.model.StoryResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Assembles the rule-based story for {@code GET /stories/{ticker}} (spec sub-project C, Task 16):
 * the last 7 DAY# rollups, the ticker's latest insight (group-aware), and the latest TS# price
 * point, then hands them to a pure {@link StoryComposer}. Read-only, matching the sibling
 * query-function routes: this path never writes and never calls Bedrock.
 *
 * <p>{@link DailyMarketDataQuery} validates and normalizes the ticker first; its response carries
 * the canonical ticker, which the remaining reads reuse so an invalid ticker fails fast on the
 * first query instead of after three.
 */
@Service
public class StoryQuery {

    private static final Logger log = LoggerFactory.getLogger(StoryQuery.class);

    // Fixed 7-day window (spec sub-project C, Task 16): the story route takes no days query
    // param, unlike the sibling daily-rollup route.
    private static final String WINDOW_DAYS = "7";

    private final DailyMarketDataQuery dailyMarketDataQuery;
    private final InsightFeedQuery insightFeedQuery;
    private final MarketDataQuery marketDataQuery;
    private final StoryComposer storyComposer;
    private final DeepAnalysisService deepAnalysisService;
    private final Clock clock;

    public StoryQuery(
            DailyMarketDataQuery dailyMarketDataQuery,
            InsightFeedQuery insightFeedQuery,
            MarketDataQuery marketDataQuery,
            StoryComposer storyComposer,
            DeepAnalysisService deepAnalysisService,
            Clock clock) {
        this.dailyMarketDataQuery = dailyMarketDataQuery;
        this.insightFeedQuery = insightFeedQuery;
        this.marketDataQuery = marketDataQuery;
        this.storyComposer = storyComposer;
        this.deepAnalysisService = deepAnalysisService;
        this.clock = clock;
    }

    public StoryResponse story(String rawTicker) {
        var dailyResponse = dailyMarketDataQuery.findDailyPoints(rawTicker, WINDOW_DAYS);
        String ticker = dailyResponse.ticker();

        Optional<FeedInsight> insight = insightFeedQuery.latestForTicker(ticker);
        Optional<MarketDataPoint> latestPoint = marketDataQuery.findLatestPoint(ticker);

        DeepAnalysisResponse analysis = deepAnalysisService.analyze(ticker);
        StoryComposer.Composition composition =
                storyComposer.compose(ticker, dailyResponse.days(), insight, latestPoint, analysis);
        StoryInputs inputs = new StoryInputs(dailyResponse.days().size(), insight.isPresent() ? 1 : 0);

        log.info(
                "Story composed. ticker={} days={} insightPresent={} found={}",
                ticker,
                inputs.days(),
                insight.isPresent(),
                composition.found());
        return new StoryResponse(
                ticker, composition.story(), Instant.now(clock).toString(), "RULE_BASED", inputs, composition.found());
    }
}
