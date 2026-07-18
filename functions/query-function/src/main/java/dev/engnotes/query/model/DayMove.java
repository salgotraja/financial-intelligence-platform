package dev.engnotes.query.model;

import java.math.BigDecimal;

/** One day's close-to-close move within a horizon window, used to surface the best and worst day. */
public record DayMove(String date, BigDecimal changePercent) {}
