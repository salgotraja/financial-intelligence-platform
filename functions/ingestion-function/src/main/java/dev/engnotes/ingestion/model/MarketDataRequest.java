package dev.engnotes.ingestion.model;

/**
 * Pipeline input: the ticker to fetch and the correlation id for tracing.
 * Shaped by the ValidateInput Pass state in the Step Functions workflow.
 * Jackson deserializes the Lambda event payload directly into this record.
 *
 * <p>{@code source} is set by the Step Functions item selector from the execution input's
 * top-level {@code source} key; {@code "on-demand"} forces insight generation downstream
 * regardless of the anomaly gate's verdict.
 */
public record MarketDataRequest(String ticker, String correlationId, String source) {}
