package dev.engnotes.query.service;

import dev.engnotes.query.model.DailyPoint;
import dev.engnotes.query.model.DeepAnalysisResponse;
import dev.engnotes.query.model.FeedInsight;
import dev.engnotes.query.model.HorizonStats;
import dev.engnotes.query.model.MarketDataPoint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Rule-based {@link StoryComposer}: turns a ticker's recent daily rollups and latest insight into
 * a deterministic narrative (spec sub-project C, Task 16). Pure: no clock, no IO, no randomness -
 * the same inputs always produce the same sentences in the same order, so callers may safely
 * memoize or diff the output.
 *
 * <p>Each sentence degrades independently: a rule that lacks the data it needs is omitted rather
 * than approximated, and the four sentences never fabricate a figure not present in the input.
 * When every rule omits, {@link #compose} returns a fixed fallback sentence instead of an empty
 * string, matching the sibling routes' "always return something" contract.
 *
 * <p>{@code latestPoint} is accepted but not read here: every current rule derives from {@code
 * days} (which {@code DailyRollupService} keeps as fresh as the latest TS# point during market
 * hours) and {@code insight}. It stays part of the method signature because the seam is the {@link
 * StoryComposer} interface, not this implementation - a future BedrockStoryComposer reads the same
 * three inputs and may use the live price where this rule-based version does not need to.
 */
@Service
public class RuleBasedStoryComposer implements StoryComposer {

    private static final BigDecimal VOLUME_THRESHOLD = new BigDecimal("0.20");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int CALC_SCALE = 6;
    private static final int DISPLAY_SCALE = 2;

    @Override
    public Composition compose(
            String ticker,
            List<DailyPoint> days,
            Optional<FeedInsight> insight,
            Optional<MarketDataPoint> latestPoint,
            DeepAnalysisResponse analysis) {
        List<String> sentences = new ArrayList<>();
        trendSentence(ticker, days).ifPresent(sentences::add);
        notableDaySentence(days).ifPresent(sentences::add);
        volumeSentence(days).ifPresent(sentences::add);
        insight.flatMap(RuleBasedStoryComposer::insightSentence).ifPresent(sentences::add);
        horizonContextSentence(ticker, analysis).ifPresent(sentences::add);
        bandSentence(analysis).ifPresent(sentences::add);

        if (sentences.isEmpty()) {
            return new Composition(
                    "Not enough history yet for " + ticker + "; the story builds as market sessions accumulate.",
                    false);
        }
        return new Composition(String.join(" ", sentences), true);
    }

    // Trend: close vs first-available close over the window. Requires >=2 days (days is
    // newest-first, so getFirst() is the latest close and getLast() the oldest in the window).
    private static Optional<String> trendSentence(String ticker, List<DailyPoint> days) {
        if (days.size() < 2) {
            return Optional.empty();
        }
        BigDecimal latestClose = days.getFirst().close();
        BigDecimal firstClose = days.getLast().close();
        if (latestClose == null || firstClose == null || firstClose.signum() == 0) {
            return Optional.empty();
        }
        BigDecimal changePercent = percentChange(latestClose, firstClose);
        int sessions = days.size();
        if (changePercent.signum() == 0) {
            return Optional.of(ticker + " is flat over the past " + sessions + " sessions.");
        }
        String direction = changePercent.signum() > 0 ? "up" : "down";
        return Optional.of(ticker + " is " + direction + " " + display(changePercent.abs()) + "% over the past "
                + sessions + " sessions.");
    }

    // Notable day: the day with the largest |close - previousClose|. Requires >=1 day carrying a
    // previousClose; days without one (e.g. the oldest day in the window) are skipped, never
    // treated as a zero move.
    private static Optional<String> notableDaySentence(List<DailyPoint> days) {
        DailyPoint notable = null;
        BigDecimal largestMove = null;
        for (DailyPoint day : days) {
            if (day.close() == null
                    || day.previousClose() == null
                    || day.previousClose().signum() == 0) {
                continue;
            }
            BigDecimal move = day.close().subtract(day.previousClose()).abs();
            if (largestMove == null || move.compareTo(largestMove) > 0) {
                largestMove = move;
                notable = day;
            }
        }
        if (notable == null) {
            return Optional.empty();
        }
        BigDecimal changePercent = percentChange(notable.close(), notable.previousClose());
        String direction = changePercent.signum() >= 0 ? "up" : "down";
        return Optional.of("The most notable move was on " + notable.date() + ": " + direction + " "
                + display(changePercent.abs()) + "%.");
    }

    // Volume: latest day's volume vs the mean of the prior days in the window, +/-20% threshold.
    // Requires >=2 days, a non-null latest volume, and at least one non-null prior volume.
    private static Optional<String> volumeSentence(List<DailyPoint> days) {
        if (days.size() < 2) {
            return Optional.empty();
        }
        Long latestVolume = days.getFirst().volume();
        if (latestVolume == null) {
            return Optional.empty();
        }
        List<Long> priorVolumes = days.subList(1, days.size()).stream()
                .map(DailyPoint::volume)
                .filter(Objects::nonNull)
                .toList();
        if (priorVolumes.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal mean = priorVolumes.stream()
                .map(BigDecimal::valueOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(priorVolumes.size()), CALC_SCALE, RoundingMode.HALF_UP);
        if (mean.signum() == 0) {
            return Optional.empty();
        }
        BigDecimal ratio = BigDecimal.valueOf(latestVolume).divide(mean, CALC_SCALE, RoundingMode.HALF_UP);
        BigDecimal deviation = ratio.subtract(BigDecimal.ONE).abs().multiply(HUNDRED);
        int windowSize = priorVolumes.size();
        if (ratio.compareTo(BigDecimal.ONE.add(VOLUME_THRESHOLD)) >= 0) {
            return Optional.of(
                    "Volume is running " + display(deviation) + "% above its " + windowSize + "-day average.");
        }
        if (ratio.compareTo(BigDecimal.ONE.subtract(VOLUME_THRESHOLD)) <= 0) {
            return Optional.of(
                    "Volume is running " + display(deviation) + "% below its " + windowSize + "-day average.");
        }
        return Optional.of("Volume is in line with its " + windowSize + "-day average.");
    }

    // Insight: latest signal + one-clause rationale + driver count. A cross-ticker (grouped)
    // insight names every ticker the group covers instead of only this one.
    private static Optional<String> insightSentence(FeedInsight insight) {
        if (insight.signal() == null || insight.signal().isBlank()) {
            return Optional.empty();
        }
        String rationale = insight.rationale() == null || insight.rationale().isBlank()
                ? "no rationale recorded"
                : insight.rationale();
        int driverCount = insight.drivers().size();
        String driverWord = driverCount == 1 ? "driver" : "drivers";
        if (insight.groupId() != null && insight.tickers().size() > 1) {
            String peers = String.join(", ", insight.tickers());
            return Optional.of("A cross-ticker insight covering " + peers + " signals " + insight.signal() + ": "
                    + rationale + " (" + driverCount + " " + driverWord + ").");
        }
        return Optional.of("The latest insight signals " + insight.signal() + ": " + rationale + " (" + driverCount
                + " " + driverWord + ").");
    }

    // Quarter/year framing from the analysis pack. Only NON-partial horizons speak: a horizon
    // computed over thin history would state a figure the window cannot support.
    private static Optional<String> horizonContextSentence(String ticker, DeepAnalysisResponse analysis) {
        if (analysis == null || !analysis.found()) {
            return Optional.empty();
        }
        Optional<HorizonStats> quarter = fullHorizon(analysis, "3M");
        Optional<HorizonStats> year = fullHorizon(analysis, "1Y");
        if (quarter.isPresent() && year.isPresent()) {
            return Optional.of("Over the past quarter " + ticker + " is " + direction(quarter.get())
                    + "; over the year, " + direction(year.get()) + ".");
        }
        return quarter.map(h -> "Over the past quarter " + ticker + " is " + direction(h) + ".")
                .or(() -> year.map(h -> "Over the past year " + ticker + " is " + direction(h) + "."));
    }

    private static Optional<HorizonStats> fullHorizon(DeepAnalysisResponse analysis, String key) {
        return analysis.horizons().stream()
                .filter(h -> key.equals(h.key()) && !h.partial() && h.returnPercent() != null)
                .findFirst();
    }

    private static String direction(HorizonStats horizon) {
        BigDecimal pct = horizon.returnPercent();
        if (pct.signum() == 0) {
            return "flat";
        }
        return (pct.signum() > 0 ? "up " : "down ") + display(pct.abs()) + "%";
    }

    // 52-week band position in thirds; silent when the band could not be established. A
    // DERIVED_1Y band is only as good as the 1Y horizon it was derived from: when that horizon is
    // partial, the "52-week" framing would overstate thin history, so the sentence stays silent.
    // HIGH_LOW_52W bands come from genuine 52-week data and are unaffected by this gate.
    private static Optional<String> bandSentence(DeepAnalysisResponse analysis) {
        if (analysis == null || !analysis.found() || analysis.band52w() == null) {
            return Optional.empty();
        }
        if ("DERIVED_1Y".equals(analysis.band52w().source())
                && fullHorizon(analysis, "1Y").isEmpty()) {
            return Optional.empty();
        }
        BigDecimal position = analysis.band52w().bandPositionPercent();
        if (position == null) {
            return Optional.empty();
        }
        String third = position.doubleValue() >= 66.67 ? "upper" : position.doubleValue() <= 33.33 ? "lower" : "middle";
        return Optional.of("It trades in the " + third + " third of its 52-week range.");
    }

    private static BigDecimal percentChange(BigDecimal current, BigDecimal base) {
        return current.subtract(base)
                .divide(base, CALC_SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED);
    }

    private static String display(BigDecimal value) {
        return value.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP).toPlainString();
    }
}
