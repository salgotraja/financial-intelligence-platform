package dev.engnotes.query.model;

import java.math.BigDecimal;

/**
 * One stored market-data observation for a ticker (written by the ingestion pipeline as a
 * PK=TICKER#{ticker}, SK=TS#{iso8601} item). Numeric fields are nullable: the ingestion path only
 * stores non-null Yahoo Finance fields, so absent attributes surface as nulls, never zeros.
 */
public record MarketDataPoint(
        String timestamp,
        BigDecimal price,
        BigDecimal previousClose,
        BigDecimal change,
        BigDecimal changePercent,
        Long volume,
        BigDecimal high52Week,
        BigDecimal low52Week) {}
