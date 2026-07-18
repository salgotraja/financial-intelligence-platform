package dev.engnotes.ingestion.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One historical daily OHLCV bar from the provider's chart history. {@code close} is always
 * non-null (bars without a close are dropped at parse time); the other fields are null when the
 * provider omitted them.
 */
public record DailyBar(
        LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, Long volume) {}
