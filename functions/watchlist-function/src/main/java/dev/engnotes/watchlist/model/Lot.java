package dev.engnotes.watchlist.model;

import dev.engnotes.watchlist.exception.WatchlistException;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single purchase lot within a {@link Holding}. {@code qty} is {@code int} since NSE equities
 * trade in whole shares (no fractional-share lots).
 */
public record Lot(LocalDate buyDate, int qty, BigDecimal price) {

    public Lot {
        if (buyDate == null) {
            throw new WatchlistException("Lot buyDate must not be null");
        }
        if (qty <= 0) {
            throw new WatchlistException("Lot qty must be strictly positive, got " + qty);
        }
        if (price == null) {
            throw new WatchlistException("Lot price must not be null");
        }
        if (price.signum() <= 0) {
            throw new WatchlistException("Lot price must be strictly positive, got " + price);
        }
    }
}
