package dev.engnotes.query.model;

/**
 * Read-path input: the ticker whose latest insight is requested, plus the correlation id for
 * tracing. Shaped by the API Gateway integration request template (ticker from the path,
 * correlationId from the request id). Jackson deserializes the Lambda event payload directly.
 */
public record QueryRequest(String ticker, String correlationId) {}
