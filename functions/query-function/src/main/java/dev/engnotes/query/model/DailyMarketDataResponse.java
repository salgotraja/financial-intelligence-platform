package dev.engnotes.query.model;

import java.util.List;

/**
 * Read-path output for {@code GET /market-data/{ticker}/daily}: up to {@code days} daily rollups,
 * newest first. {@code found} is false only when no {@code DAY#} rollup exists for the ticker at all
 * (a normal empty result, not an error), matching the sibling market-data route's semantics: 404 is
 * never returned for a missing ticker.
 */
public record DailyMarketDataResponse(String ticker, List<DailyPoint> days, boolean found) {

    public static DailyMarketDataResponse notFound(String ticker) {
        return new DailyMarketDataResponse(ticker, List.of(), false);
    }
}
