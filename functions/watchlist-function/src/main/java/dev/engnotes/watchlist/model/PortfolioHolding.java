package dev.engnotes.watchlist.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * LIST response view of a stored holding: the lots plus the derived {@code totalQty}/{@code avgCost}
 * ({@link dev.engnotes.watchlist.portfolio.HoldingMath}) and the store's bookkeeping timestamps.
 * {@code lastLotMutation} is null when no existing lot has ever been edited or removed.
 */
public record PortfolioHolding(
        String ticker, List<Lot> lots, long totalQty, BigDecimal avgCost, Instant lastLotMutation, Instant updatedAt) {

    public PortfolioHolding {
        lots = lots == null ? List.of() : List.copyOf(lots);
    }
}
