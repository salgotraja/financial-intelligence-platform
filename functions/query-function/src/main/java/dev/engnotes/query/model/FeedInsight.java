package dev.engnotes.query.model;

import java.util.List;

/**
 * One entry in the watchlist insight feed (GET /insights, spec section 10). A group insight
 * (cross-ticker correlation, {@code groupId} present) covers every ticker in {@code tickers}; an
 * ungrouped per-ticker insight has {@code groupId} null and {@code tickers} holding the single
 * ticker it was generated for.
 */
public record FeedInsight(
        String groupId,
        List<String> tickers,
        String generatedAt,
        String signal,
        double confidence,
        String rationale,
        List<String> drivers,
        String source) {

    public FeedInsight {
        tickers = tickers == null ? List.of() : List.copyOf(tickers);
        drivers = drivers == null ? List.of() : List.copyOf(drivers);
    }
}
