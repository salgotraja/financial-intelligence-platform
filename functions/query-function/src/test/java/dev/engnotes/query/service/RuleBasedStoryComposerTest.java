package dev.engnotes.query.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.query.model.Band52w;
import dev.engnotes.query.model.DailyPoint;
import dev.engnotes.query.model.DeepAnalysisResponse;
import dev.engnotes.query.model.FeedInsight;
import dev.engnotes.query.model.HorizonStats;
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
                composer.compose(TICKER, richWeek(), Optional.of(ungroupedInsight()), Optional.empty(), null);

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

        Composition composition = composer.compose(TICKER, sparse, Optional.empty(), Optional.empty(), null);

        assertThat(composition.story())
                .isEqualTo("RELIANCE.NS is up 4.00% over the past 2 sessions."
                        + " The most notable move was on 2026-07-14: up 4.00%."
                        + " Volume is running 25.00% above its 1-day average.");
        assertThat(composition.found()).isTrue();
    }

    @Test
    void noInsightOmitsTheInsightSentenceOnly() {
        Composition composition = composer.compose(TICKER, richWeek(), Optional.empty(), Optional.empty(), null);

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

        Composition composition = composer.compose(TICKER, List.of(), Optional.empty(), Optional.of(livePrice), null);

        assertThat(composition.story())
                .isEqualTo("Not enough history yet for RELIANCE.NS; the story builds as market sessions accumulate.");
        assertThat(composition.found()).isFalse();
    }

    @Test
    void sameInputProducesTheSameOutputEveryTime() {
        List<DailyPoint> days = richWeek();
        Optional<FeedInsight> insight = Optional.of(ungroupedInsight());

        Composition first = composer.compose(TICKER, days, insight, Optional.empty(), null);
        Composition second = composer.compose(TICKER, days, insight, Optional.empty(), null);

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

        Composition composition = composer.compose(TICKER, List.of(), Optional.of(grouped), Optional.empty(), null);

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

        Composition composition = composer.compose(TICKER, days, Optional.empty(), Optional.empty(), null);

        assertThat(composition.story()).contains("Volume is running 20.00% above its 2-day average.");
    }

    @Test
    void volumeExactlyTwentyPercentBelowTheMeanReadsBelow() {
        List<DailyPoint> days = List.of(
                point("2026-07-14", "100", "99", 800_000L),
                point("2026-07-13", "99", "98", 1_000_000L),
                point("2026-07-12", "98", "97", 1_000_000L));

        Composition composition = composer.compose(TICKER, days, Optional.empty(), Optional.empty(), null);

        assertThat(composition.story()).contains("Volume is running 20.00% below its 2-day average.");
    }

    private static DeepAnalysisResponse analysisWithQuarterYearAndBand() {
        HorizonStats quarter = new HorizonStats(
                "3M", 67, false, new BigDecimal("12.50"), null, null, null, null, null, null, 40, 26, null, null);
        HorizonStats year = new HorizonStats(
                "1Y", 251, false, new BigDecimal("28.30"), null, null, null, null, null, null, 150, 100, null, null);
        HorizonStats week = new HorizonStats(
                "1W", 6, false, new BigDecimal("1.10"), null, null, null, null, null, null, 3, 2, null, null);
        HorizonStats month = new HorizonStats(
                "1M", 23, false, new BigDecimal("4.00"), null, null, null, null, null, null, 12, 10, null, null);
        Band52w band =
                new Band52w(new BigDecimal("130"), new BigDecimal("90"), new BigDecimal("75.00"), "HIGH_LOW_52W");
        return new DeepAnalysisResponse(
                TICKER, "2026-07-17T10:00:00Z", List.of(week, month, quarter, year), band, true);
    }

    @Test
    void analysisPackAddsQuarterYearAndBandSentences() {
        Composition composition = composer.compose(
                TICKER, richWeek(), Optional.empty(), Optional.empty(), analysisWithQuarterYearAndBand());

        assertThat(composition.story())
                .contains("Over the past quarter " + TICKER + " is up 12.50%; over the year, up 28.30%.")
                .contains("It trades in the upper third of its 52-week range.");
        assertThat(composition.found()).isTrue();
    }

    @Test
    void derived1YBandWithPartialYearHorizonAddsNoBandSentence() {
        HorizonStats partialYear = new HorizonStats(
                "1Y", 90, true, new BigDecimal("28.30"), null, null, null, null, null, null, 50, 40, null, null);
        Band52w derivedBand =
                new Band52w(new BigDecimal("130"), new BigDecimal("90"), new BigDecimal("75.00"), "DERIVED_1Y");
        var withBand =
                new DeepAnalysisResponse(TICKER, "2026-07-17T10:00:00Z", List.of(partialYear), derivedBand, true);
        var withoutBand = new DeepAnalysisResponse(TICKER, "2026-07-17T10:00:00Z", List.of(partialYear), null, true);

        Composition composition = composer.compose(TICKER, richWeek(), Optional.empty(), Optional.empty(), withBand);
        Composition baseline = composer.compose(TICKER, richWeek(), Optional.empty(), Optional.empty(), withoutBand);

        assertThat(composition.story()).doesNotContain("52-week range");
        assertThat(composition.story()).isEqualTo(baseline.story());
    }

    @Test
    void partialHorizonsAndMissingBandAddNoSentences() {
        HorizonStats partialQuarter = new HorizonStats(
                "3M", 6, true, new BigDecimal("6.00"), null, null, null, null, null, null, 3, 2, null, null);
        var analysis = new DeepAnalysisResponse(TICKER, "2026-07-17T10:00:00Z", List.of(partialQuarter), null, true);

        Composition withPack = composer.compose(TICKER, richWeek(), Optional.empty(), Optional.empty(), analysis);
        Composition withoutPack = composer.compose(TICKER, richWeek(), Optional.empty(), Optional.empty(), null);

        assertThat(withPack.story()).isEqualTo(withoutPack.story()); // partial data adds nothing
    }

    @Test
    void bandPositionExactly66_67ReadsUpper() {
        HorizonStats year = new HorizonStats(
                "1Y", 251, false, new BigDecimal("28.30"), null, null, null, null, null, null, 150, 100, null, null);
        Band52w band =
                new Band52w(new BigDecimal("130"), new BigDecimal("90"), new BigDecimal("66.67"), "HIGH_LOW_52W");
        var analysis = new DeepAnalysisResponse(TICKER, "2026-07-17T10:00:00Z", List.of(year), band, true);

        Composition composition = composer.compose(TICKER, richWeek(), Optional.empty(), Optional.empty(), analysis);

        assertThat(composition.story()).contains("It trades in the upper third of its 52-week range.");
    }

    @Test
    void bandPositionExactly33_33ReadsLower() {
        HorizonStats year = new HorizonStats(
                "1Y", 251, false, new BigDecimal("28.30"), null, null, null, null, null, null, 150, 100, null, null);
        Band52w band =
                new Band52w(new BigDecimal("130"), new BigDecimal("90"), new BigDecimal("33.33"), "HIGH_LOW_52W");
        var analysis = new DeepAnalysisResponse(TICKER, "2026-07-17T10:00:00Z", List.of(year), band, true);

        Composition composition = composer.compose(TICKER, richWeek(), Optional.empty(), Optional.empty(), analysis);

        assertThat(composition.story()).contains("It trades in the lower third of its 52-week range.");
    }

    @Test
    void bandPositionJustBelow66_67ReadsMiddle() {
        HorizonStats year = new HorizonStats(
                "1Y", 251, false, new BigDecimal("28.30"), null, null, null, null, null, null, 150, 100, null, null);
        Band52w band =
                new Band52w(new BigDecimal("130"), new BigDecimal("90"), new BigDecimal("66.66"), "HIGH_LOW_52W");
        var analysis = new DeepAnalysisResponse(TICKER, "2026-07-17T10:00:00Z", List.of(year), band, true);

        Composition composition = composer.compose(TICKER, richWeek(), Optional.empty(), Optional.empty(), analysis);

        assertThat(composition.story()).contains("It trades in the middle third of its 52-week range.");
    }

    @Test
    void bandPositionJustAbove33_33ReadsMiddle() {
        HorizonStats year = new HorizonStats(
                "1Y", 251, false, new BigDecimal("28.30"), null, null, null, null, null, null, 150, 100, null, null);
        Band52w band =
                new Band52w(new BigDecimal("130"), new BigDecimal("90"), new BigDecimal("33.34"), "HIGH_LOW_52W");
        var analysis = new DeepAnalysisResponse(TICKER, "2026-07-17T10:00:00Z", List.of(year), band, true);

        Composition composition = composer.compose(TICKER, richWeek(), Optional.empty(), Optional.empty(), analysis);

        assertThat(composition.story()).contains("It trades in the middle third of its 52-week range.");
    }
}
