package dev.engnotes.watchlist.model;

import java.math.BigDecimal;

/**
 * A single ticker's priced position within a {@link PortfolioValuation}. {@code avgCost} is always
 * present (derived from lots alone). {@code ltp}/{@code dayChange}/{@code pnl}/{@code pnlPct}/
 * {@code asOf} are {@code null} when {@code degraded} is {@code true} (no TS# or DAY# price data
 * found for the ticker); {@code dayChange} may also be {@code null} on a non-degraded row when the
 * two most recent DAY# rollups are not consecutive trading days, or only one rollup exists.
 */
public record HoldingValuation(
        String ticker,
        long qty,
        BigDecimal avgCost,
        BigDecimal ltp,
        BigDecimal dayChange,
        BigDecimal pnl,
        BigDecimal pnlPct,
        String asOf,
        boolean degraded) {}
