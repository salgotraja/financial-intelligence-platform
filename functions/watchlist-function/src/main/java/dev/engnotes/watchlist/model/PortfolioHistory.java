package dev.engnotes.watchlist.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * The HISTORY response payload: a time-machine value-curve for the owner's portfolio. {@code floor}
 * ({@code yyyy-MM-dd}) is the earliest day the curve can honestly start (bounded by both the oldest
 * daily-rollup data and the most recent lot edit/removal), {@code null} when there are no priceable
 * holdings. {@code asOf} is the last curve day, {@code null} when {@code points} is empty. {@code
 * degradedTickers} lists holdings with no DAY# rollup data at all; they are excluded from the curve.
 *
 * <p>{@code benchmark} is an E7 NIFTY (^NSEI) overlay: the portfolio's value curve re-expressed as
 * "if this money had instead tracked the NIFTY from the overlay start". It is normalized so {@code
 * benchmark}'s first point equals the portfolio's value at {@code benchmarkFrom}, then scaled day by
 * day by the index's own return. {@code benchmarkFrom} ({@code yyyy-MM-dd}) is that overlay start day
 * - the later of the portfolio's {@code floor} and the earliest day NIFTY rollup data is available -
 * {@code null} when no overlay can be computed (no NIFTY data, or it doesn't cover any curve day).
 * The overlay clips to index availability; {@code points} is never truncated to match it. {@code
 * beatBenchmarkPct} is the portfolio's percentage return over the overlay window minus the NIFTY's
 * percentage return over the same window, {@code null} when there is no overlay.
 */
public record PortfolioHistory(
        String floor,
        String asOf,
        List<HistoryPoint> points,
        List<BuyMarker> markers,
        List<String> degradedTickers,
        List<HistoryPoint> benchmark,
        String benchmarkFrom,
        BigDecimal beatBenchmarkPct) {

    public PortfolioHistory {
        points = points == null ? List.of() : List.copyOf(points);
        markers = markers == null ? List.of() : List.copyOf(markers);
        degradedTickers = degradedTickers == null ? List.of() : List.copyOf(degradedTickers);
        benchmark = benchmark == null ? List.of() : List.copyOf(benchmark);
    }
}
