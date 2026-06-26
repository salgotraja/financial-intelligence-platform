package dev.engnotes.ingestion.model;

/**
 * Pipeline input: the ticker to fetch and the correlation id for tracing.
 * Shaped by the ValidateInput Pass state in the Step Functions workflow.
 * Jackson deserializes the Lambda event payload directly into this record.
 */
public record MarketDataRequest(String ticker, String correlationId) {}
