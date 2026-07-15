package dev.engnotes.query.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.query.model.DailyPoint;
import dev.engnotes.query.model.FeedInsight;
import dev.engnotes.query.model.MarketDataPoint;
import dev.engnotes.query.service.StoryComposer.Composition;
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
    void richFixtureProducesAllFourSentencesAndFoundTrue() {
        Composition composition =
                composer.compose(TICKER, richWeek(), Optional.of(ungroupedInsight()), Optional.empty());

        assertThat(composition.story())
                .isEqualTo("RELIANCE.NS is up 10.00% over the past 7 sessions."
                        + " The most notable move was on 2026-07-11: up 16.67%."
                        + " Volume is running 100.00% above its 6-day average."
                        + " The latest insight signals BULLISH: steady gains driven by strong earnings (2 drivers).");
        assertThat(composition.found()).isTrue();
    }

    @Test
    void sparseTwoDayFixtureDegradesToWhatExistsAndFoundTrue() {
        List<DailyPoint> sparse = List.of(
                point("2026-07-14", "52", "50", 500_000L),
                new DailyPoint("2026-07-13", null, null, null, new BigDecimal("50"), null, 400_000L));

        Composition composition = composer.compose(TICKER, sparse, Optional.empty(), Optional.empty());

        assertThat(composition.story())
                .isEqualTo("RELIANCE.NS is up 4.00% over the past 2 sessions."
                        + " The most notable move was on 2026-07-14: up 4.00%."
                        + " Volume is running 25.00% above its 1-day average.");
        assertThat(composition.found()).isTrue();
    }

    @Test
    void noInsightOmitsTheInsightSentenceOnly() {
        Composition composition = composer.compose(TICKER, richWeek(), Optional.empty(), Optional.empty());

        assertThat(composition.story())
                .isEqualTo("RELIANCE.NS is up 10.00% over the past 7 sessions."
                        + " The most notable move was on 2026-07-11: up 16.67%."
                        + " Volume is running 100.00% above its 6-day average.");
        assertThat(composition.story()).doesNotContain("insight signals");
        assertThat(composition.found()).isTrue();
    }

    @Test
    void nothingComposesFallsBackWithFoundFalseAndNeverFabricates() {
        MarketDataPoint livePrice =
                new MarketDataPoint("2026-07-14T10:00:00Z", new BigDecimal("110"), null, null, null, null, null, null);

        Composition composition = composer.compose(TICKER, List.of(), Optional.empty(), Optional.of(livePrice));

        assertThat(composition.story())
                .isEqualTo("Not enough history yet for RELIANCE.NS; the story builds as market sessions accumulate.");
        assertThat(composition.found()).isFalse();
    }

    @Test
    void sameInputProducesTheSameOutputEveryTime() {
        List<DailyPoint> days = richWeek();
        Optional<FeedInsight> insight = Optional.of(ungroupedInsight());

        Composition first = composer.compose(TICKER, days, insight, Optional.empty());
        Composition second = composer.compose(TICKER, days, insight, Optional.empty());

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

        Composition composition = composer.compose(TICKER, List.of(), Optional.of(grouped), Optional.empty());

        assertThat(composition.story())
                .isEqualTo("A cross-ticker insight covering RELIANCE.NS, TCS.NS signals BEARISH:"
                        + " correlated sector pullback (1 driver).");
        assertThat(composition.found()).isTrue();
    }

    // The +/-20% threshold is inclusive on both edges: a ratio of exactly 1.20 reads "above" and
    // exactly 0.80 reads "below", never "in line". Pins the >= / <= comparisons so a future
    // rewrite to strict inequality fails here instead of silently reclassifying boundary days.
    @Test
    void volumeExactlyTwentyPercentAboveTheMeanReadsAbove() {
        List<DailyPoint> days = List.of(
                point("2026-07-14", "100", "99", 1_200_000L),
                point("2026-07-13", "99", "98", 1_000_000L),
                point("2026-07-12", "98", "97", 1_000_000L));

        Composition composition = composer.compose(TICKER, days, Optional.empty(), Optional.empty());

        assertThat(composition.story()).contains("Volume is running 20.00% above its 2-day average.");
    }

    @Test
    void volumeExactlyTwentyPercentBelowTheMeanReadsBelow() {
        List<DailyPoint> days = List.of(
                point("2026-07-14", "100", "99", 800_000L),
                point("2026-07-13", "99", "98", 1_000_000L),
                point("2026-07-12", "98", "97", 1_000_000L));

        Composition composition = composer.compose(TICKER, days, Optional.empty(), Optional.empty());

        assertThat(composition.story()).contains("Volume is running 20.00% below its 2-day average.");
    }
}
