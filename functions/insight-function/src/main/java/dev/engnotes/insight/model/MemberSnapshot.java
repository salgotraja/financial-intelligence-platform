package dev.engnotes.insight.model;

import java.math.BigDecimal;

/**
 * One group member's latest priced state for cross-ticker context assembly: the newest TS# point
 * (price, percent change, volume) plus the ticker's BASELINE volume stats (mean and standard
 * deviation, Welford-derived, spec section 6) so a rule-based or Bedrock insight can describe
 * volume relative to the ticker's own history. Any field may be {@code null} when the member has no
 * priced point yet or no warmed-up baseline; readers must treat that as "unavailable", not zero.
 */
public record MemberSnapshot(
        String ticker,
        BigDecimal price,
        BigDecimal changePercent,
        Long volume,
        Double baselineVolumeMean,
        Double baselineVolumeStdDev) {}
