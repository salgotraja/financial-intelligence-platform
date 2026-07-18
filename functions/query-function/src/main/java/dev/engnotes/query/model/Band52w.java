package dev.engnotes.query.model;

import java.math.BigDecimal;

/**
 * The 52-week high/low band and where the latest close sits within it; {@code source} is either
 * {@code HIGH_LOW_52W} (from the stored latest point) or {@code DERIVED_1Y} (computed from the
 * daily-rollup window when no latest point carries 52-week fields).
 */
public record Band52w(BigDecimal high, BigDecimal low, BigDecimal bandPositionPercent, String source) {}
