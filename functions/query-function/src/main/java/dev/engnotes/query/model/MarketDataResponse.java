package dev.engnotes.query.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-path output for GET /market-data/{ticker}: recent stored points, newest first, plus the
 * latest NSE trading day's intraday price curve read from the no-TTL DAY# rollup item. {@code
 * daySeries} is chronological (oldest minute first); {@code previousClose} and {@code day} come from
 * the same DAY# item and may be null when it is absent. {@code found} is false only when neither a
 * recent point nor a day curve exists (a normal empty result, not an error), matching the insight
 * route's semantics: 404 is never returned for a missing ticker.
 */
public record MarketDataResponse(
        String ticker,
        List<MarketDataPoint> points,
        boolean found,
        List<SeriesPoint> daySeries,
        BigDecimal previousClose,
        String day) {

    public static MarketDataResponse notFound(String ticker) {
        return new MarketDataResponse(ticker, List.of(), false, List.of(), null, null);
    }
}
