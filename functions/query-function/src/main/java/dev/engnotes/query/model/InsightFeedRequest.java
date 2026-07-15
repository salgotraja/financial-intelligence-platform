package dev.engnotes.query.model;

/**
 * Read-path input for GET /insights: the caller's sub (from the API authorizer context, never the
 * request body) plus the correlation id for tracing. Shaped by the API Gateway integration request
 * template. Jackson deserializes the Lambda event payload directly.
 */
public record InsightFeedRequest(String ownerSub, String correlationId) {}
