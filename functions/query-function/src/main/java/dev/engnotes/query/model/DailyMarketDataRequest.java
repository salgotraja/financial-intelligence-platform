package dev.engnotes.query.model;

/**
 * Read-path input for {@code GET /market-data/{ticker}/daily}: the ticker plus the raw {@code days}
 * query-string value (may be null/blank/non-numeric; {@link
 * dev.engnotes.query.service.DailyMarketDataQuery} parses, defaults, and clamps it), and the
 * correlation id for tracing. Shaped by the API Gateway integration request template.
 */
public record DailyMarketDataRequest(String ticker, String days, String correlationId) {}
