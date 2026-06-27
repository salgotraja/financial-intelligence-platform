package dev.engnotes.watchlist.model;

import java.util.List;

/**
 * Watchlist output. ADD returns {@code status=added}; REMOVE returns {@code status=removed} (a
 * no-op delete of an untracked ticker is still {@code removed}); LIST returns {@code status=ok}
 * with {@code tickers} populated. The unused fields are null/empty per operation.
 */
public record WatchlistResponse(String status, String ticker, List<String> tickers) {

    public static WatchlistResponse added(String ticker) {
        return new WatchlistResponse("added", ticker, null);
    }

    public static WatchlistResponse removed(String ticker) {
        return new WatchlistResponse("removed", ticker, null);
    }

    public static WatchlistResponse list(List<String> tickers) {
        return new WatchlistResponse("ok", null, tickers);
    }
}
