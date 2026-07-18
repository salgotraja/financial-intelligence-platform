package dev.engnotes.query.model;

import java.math.BigDecimal;

/** Deterministic stats for one horizon window ({@code 1W}/{@code 1M}/{@code 3M}/{@code 1Y}) over daily closes. */
public record HorizonStats(
        String key,
        int daysAvailable,
        boolean partial,
        BigDecimal returnPercent,
        BigDecimal high,
        BigDecimal low,
        BigDecimal volatilityPercent,
        BigDecimal maxDrawdownPercent,
        DayMove bestDay,
        DayMove worstDay,
        int upDays,
        int downDays,
        Long avgVolume,
        BigDecimal volumeTrendPercent) {}
