package dev.engnotes.watchlist.model;

import dev.engnotes.watchlist.exception.WatchlistException;
import java.util.List;

/** A ticker's full position: the ordered set of purchase lots that make it up. */
public record Holding(String ticker, List<Lot> lots) {

    private static final int MAX_LOTS = 50;

    public Holding {
        if (ticker == null || ticker.isBlank()) {
            throw new WatchlistException("Holding ticker must not be blank");
        }
        if (lots == null) {
            throw new WatchlistException("Holding lots must not be null");
        }
        lots = List.copyOf(lots);
        if (lots.isEmpty() || lots.size() > MAX_LOTS) {
            throw new WatchlistException(
                    "Holding lots size must be between 1 and " + MAX_LOTS + ", got " + lots.size());
        }
    }
}
