package dev.engnotes.query.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.query.model.DailyPoint;
import dev.engnotes.query.model.FeedInsight;
import dev.engnotes.query.model.MarketDataPoint;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RuleBasedStoryComposerTest {

    private static final String TICKER = "RELIANCE.NS";

    private final RuleBasedStoryComposer composer = new RuleBasedStoryComposer();

    // Newest first, matching DailyMarketDataQuery's scanIndexForward(false) ordering.
    private static List<DailyPoint> richWeek() {
        return List.of(
                point("2026-07-14", "110", "108", 2_000_000L),
                point("2026-07-13", "108", "107", 1_000_000L),
                point("2026-07-12", "107", "105", 1_000_000L),
                point("2026-07-11", "105", "90", 1_000_000L),
                point("2026-07-10", "90", "92", 1_000_000L),
                point("2026-07-09", "92", "95", 1_000_000L),
                new DailyPoint("2026-07-08", null, null, null, new BigDecimal("100"), null, 1_000_000L));
    }

    private static DailyPoint point(String date, String close, String previousClose, Long volume) {
        return new DailyPoint(date, null, null, null, new BigDecimal(close), new BigDecimal(previousClose), volume);
    }

    private static FeedInsight ungroupedInsight() {
        return new FeedInsight(
                null,
                List.of(TICKER),
                "2026-07-14T09:00:00Z",
                "BULLISH",
                0.8,
                "steady gains driven by strong earnings",
                List.of("earnings", "momentum"),
                "RULE_BASED");
    }

    @Test
    void richFixtureProducesAllFourSentences() {
        String story = composer.compose(TICKER, richWeek(), Optional.of(ungroupedInsight()), Optional.empty());

        assertThat(story)
                .isEqualTo("RELIANCE.NS is up 10.00% over the past 7 sessions."
                        + " The most notable move was on 2026-07-11: up 16.67%."
                        + " Volume is running 100.00% above its 6-day average."
                        + " The latest insight signals BULLISH: steady gains driven by strong earnings (2 drivers).");
    }

    @Test
    void sparseTwoDayFixtureDegradesToWhatExists() {
        List<DailyPoint> sparse = List.of(
                point("2026-07-14", "52", "50", 500_000L),
                new DailyPoint("2026-07-13", null, null, null, new BigDecimal("50"), null, 400_000L));

        String story = composer.compose(TICKER, sparse, Optional.empty(), Optional.empty());

        assertThat(story)
                .isEqualTo("RELIANCE.NS is up 4.00% over the past 2 sessions."
                        + " The most notable move was on 2026-07-14: up 4.00%."
                        + " Volume is running 25.00% above its 1-day average.");
    }

    @Test
    void noInsightOmitsTheInsightSentenceOnly() {
        String story = composer.compose(TICKER, richWeek(), Optional.empty(), Optional.empty());

        assertThat(story)
                .isEqualTo("RELIANCE.NS is up 10.00% over the past 7 sessions."
                        + " The most notable move was on 2026-07-11: up 16.67%."
                        + " Volume is running 100.00% above its 6-day average.");
        assertThat(story).doesNotContain("insight signals");
    }

    @Test
    void nothingComposesFallsBackToTheFixedSentenceAndNeverFabricates() {
        MarketDataPoint livePrice =
                new MarketDataPoint("2026-07-14T10:00:00Z", new BigDecimal("110"), null, null, null, null, null, null);

        String story = composer.compose(TICKER, List.of(), Optional.empty(), Optional.of(livePrice));

        assertThat(story)
                .isEqualTo("Not enough history yet for RELIANCE.NS; the story builds as market sessions accumulate.");
    }

    @Test
    void sameInputProducesTheSameOutputEveryTime() {
        List<DailyPoint> days = richWeek();
        Optional<FeedInsight> insight = Optional.of(ungroupedInsight());

        String first = composer.compose(TICKER, days, insight, Optional.empty());
        String second = composer.compose(TICKER, days, insight, Optional.empty());

        assertThat(first).isEqualTo(second);
    }

    @Test
    void crossTickerInsightNamesEveryTickerInTheGroup() {
        FeedInsight grouped = new FeedInsight(
                "G1",
                List.of("RELIANCE.NS", "TCS.NS"),
                "2026-07-14T09:00:00Z",
                "BEARISH",
                0.6,
                "correlated sector pullback",
                List.of("sector rotation"),
                "BEDROCK");

        String story = composer.compose(TICKER, List.of(), Optional.of(grouped), Optional.empty());

        assertThat(story)
                .isEqualTo("A cross-ticker insight covering RELIANCE.NS, TCS.NS signals BEARISH:"
                        + " correlated sector pullback (1 driver).");
    }
}
