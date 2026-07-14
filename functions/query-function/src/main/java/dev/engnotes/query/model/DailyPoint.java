package dev.engnotes.query.model;

import java.math.BigDecimal;

/**
 * One daily OHLCV rollup, read from the no-TTL {@code DAY#} item written by ingestion's
 * DailyRollupService. Deliberately omits the item's intraday {@code series} blob: this route feeds
 * weekly/period charts, so keeping the payload to one row per day matters more than the minute-level
 * curve (that stays on {@code GET /market-data/{ticker}}, spec sub-project A).
 */
public record DailyPoint(
        String date,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal previousClose,
        Long volume) {}
