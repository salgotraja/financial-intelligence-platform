package dev.engnotes.query.model;

import java.util.List;

/**
 * Read-path output for GET /market-data/{ticker}: recent stored points, newest first. {@code found}
 * is false when no points exist (a normal empty result, not an error), matching the insight route's
 * semantics: 404 is never returned for a missing ticker.
 */
public record MarketDataResponse(String ticker, List<MarketDataPoint> points, boolean found) {

    public static MarketDataResponse notFound(String ticker) {
        return new MarketDataResponse(ticker, List.of(), false);
    }
}
