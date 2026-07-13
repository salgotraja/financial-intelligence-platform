package dev.engnotes.query.model;

import java.math.BigDecimal;

/**
 * One intraday observation on the latest NSE trading day's price curve, read from the no-TTL DAY#
 * rollup item ({@code series} list). {@code time} is the market-clock label "HH:mm" as stored by the
 * ingestion pipeline; {@code price} is the last price at that minute.
 */
public record SeriesPoint(String time, BigDecimal price) {}
