package dev.engnotes.watchlist.model;

import java.util.List;

/**
 * The HISTORY response payload: a time-machine value-curve for the owner's portfolio. {@code floor}
 * ({@code yyyy-MM-dd}) is the earliest day the curve can honestly start (bounded by both the oldest
 * daily-rollup data and the most recent lot edit/removal), {@code null} when there are no priceable
 * holdings. {@code asOf} is the last curve day, {@code null} when {@code points} is empty. {@code
 * degradedTickers} lists holdings with no DAY# rollup data at all; they are excluded from the curve.
 */
public record PortfolioHistory(
        String floor, String asOf, List<HistoryPoint> points, List<BuyMarker> markers, List<String> degradedTickers) {

    public PortfolioHistory {
        points = points == null ? List.of() : List.copyOf(points);
        markers = markers == null ? List.of() : List.copyOf(markers);
        degradedTickers = degradedTickers == null ? List.of() : List.copyOf(degradedTickers);
    }
}
