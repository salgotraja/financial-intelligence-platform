package dev.engnotes.watchlist.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * The LIST response payload: the owner's priced holdings plus portfolio-level totals summed over
 * the non-degraded rows only (a degraded row still appears in {@code holdings} but contributes
 * nothing to the totals). {@code asOf} is the lexicographically smallest non-null per-holding
 * {@code asOf} across all rows, {@code null} if every row is degraded/has no {@code asOf}.
 */
public record PortfolioValuation(
        String asOf,
        BigDecimal totalValue,
        BigDecimal totalCost,
        BigDecimal totalPnl,
        BigDecimal totalDayChange,
        List<HoldingValuation> holdings) {

    public PortfolioValuation {
        holdings = holdings == null ? List.of() : List.copyOf(holdings);
    }
}
