package dev.engnotes.watchlist.model;

import java.math.BigDecimal;

/** One point on the portfolio value-curve: {@code day} is {@code yyyy-MM-dd}, {@code value} is display-scale (2dp). */
public record HistoryPoint(String day, BigDecimal value) {}
