package dev.engnotes.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.query.model.DailyMarketDataResponse;
import dev.engnotes.query.model.DailyPoint;
import dev.engnotes.query.model.DeepAnalysisResponse;
import dev.engnotes.query.model.FeedInsight;
import dev.engnotes.query.model.MarketDataPoint;
import dev.engnotes.query.model.StoryResponse;
import dev.engnotes.query.service.StoryComposer.Composition;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoryQueryTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-14T10:00:00Z");

    @Mock
    private DailyMarketDataQuery dailyMarketDataQuery;

    @Mock
    private InsightFeedQuery insightFeedQuery;

    @Mock
    private MarketDataQuery marketDataQuery;

    @Mock
    private StoryComposer storyComposer;

    @Mock
    private DeepAnalysisService deepAnalysisService;

    private StoryQuery storyQuery;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        storyQuery = new StoryQuery(
                dailyMarketDataQuery, insightFeedQuery, marketDataQuery, storyComposer, deepAnalysisService, clock);
    }

    @Test
    void assemblesInputsAndDelegatesToTheComposer() {
        List<DailyPoint> days = List.of(new DailyPoint("2026-07-14", null, null, null, null, null, null));
        when(dailyMarketDataQuery.findDailyPoints("RELIANCE.NS", "7"))
                .thenReturn(new DailyMarketDataResponse("RELIANCE.NS", days, true));
        FeedInsight insight = new FeedInsight(
                null, List.of("RELIANCE.NS"), "2026-07-14T09:00:00Z", "BULLISH", 0.8, "r", List.of("d"), "RULE_BASED");
        when(insightFeedQuery.latestForTicker("RELIANCE.NS")).thenReturn(Optional.of(insight));
        MarketDataPoint latest = new MarketDataPoint("2026-07-14T09:55:00Z", null, null, null, null, null, null, null);
        when(marketDataQuery.findLatestPoint("RELIANCE.NS")).thenReturn(Optional.of(latest));
        DeepAnalysisResponse analysis = DeepAnalysisResponse.notFound("RELIANCE.NS", FIXED_NOW.toString());
        when(deepAnalysisService.analyze("RELIANCE.NS")).thenReturn(analysis);
        when(storyComposer.compose(
                        eq("RELIANCE.NS"), eq(days), eq(Optional.of(insight)), eq(Optional.of(latest)), eq(analysis)))
                .thenReturn(new Composition("a composed story", true));

        StoryResponse response = storyQuery.story("RELIANCE.NS");

        assertThat(response.ticker()).isEqualTo("RELIANCE.NS");
        assertThat(response.story()).isEqualTo("a composed story");
        assertThat(response.source()).isEqualTo("RULE_BASED");
        assertThat(response.generatedAt()).isEqualTo(FIXED_NOW.toString());
        assertThat(response.inputs().days()).isEqualTo(1);
        assertThat(response.inputs().insightCount()).isEqualTo(1);
        assertThat(response.found()).isTrue();
    }

    @Test
    void usesTheCanonicalTickerFromTheDailyResponseForSubsequentReads() {
        // A percent-encoded index symbol normalizes to its canonical form (Tickers.validated,
        // DailyMarketDataQuery); every later read must use that canonical ticker, not the raw path
        // value, or the insight/price lookups would miss the data stored under the canonical key.
        when(dailyMarketDataQuery.findDailyPoints(any(), any()))
                .thenReturn(new DailyMarketDataResponse("^NSEI", List.of(), false));
        when(insightFeedQuery.latestForTicker("^NSEI")).thenReturn(Optional.empty());
        when(marketDataQuery.findLatestPoint("^NSEI")).thenReturn(Optional.empty());
        when(deepAnalysisService.analyze("^NSEI"))
                .thenReturn(DeepAnalysisResponse.notFound("^NSEI", FIXED_NOW.toString()));
        when(storyComposer.compose(eq("^NSEI"), anyList(), any(), any(), any()))
                .thenReturn(new Composition("fallback", false));

        StoryResponse response = storyQuery.story("%5ENSEI");

        assertThat(response.ticker()).isEqualTo("^NSEI");
        verify(insightFeedQuery).latestForTicker("^NSEI");
        verify(insightFeedQuery, never()).latestForTicker("%5ENSEI");
    }

    @Test
    void emptyDataStillReturnsAStoryWithZeroCounts() {
        when(dailyMarketDataQuery.findDailyPoints("NEWTICKER", "7"))
                .thenReturn(DailyMarketDataResponse.notFound("NEWTICKER"));
        when(insightFeedQuery.latestForTicker("NEWTICKER")).thenReturn(Optional.empty());
        when(marketDataQuery.findLatestPoint("NEWTICKER")).thenReturn(Optional.empty());
        when(deepAnalysisService.analyze("NEWTICKER"))
                .thenReturn(DeepAnalysisResponse.notFound("NEWTICKER", FIXED_NOW.toString()));
        when(storyComposer.compose(eq("NEWTICKER"), eq(List.of()), eq(Optional.empty()), eq(Optional.empty()), any()))
                .thenReturn(new Composition(
                        "Not enough history yet for NEWTICKER; the story builds as market sessions accumulate.",
                        false));

        StoryResponse response = storyQuery.story("NEWTICKER");

        assertThat(response.inputs().days()).isEqualTo(0);
        assertThat(response.inputs().insightCount()).isEqualTo(0);
        assertThat(response.story())
                .isEqualTo("Not enough history yet for NEWTICKER; the story builds as market sessions accumulate.");
        assertThat(response.found()).isFalse();
    }
}
