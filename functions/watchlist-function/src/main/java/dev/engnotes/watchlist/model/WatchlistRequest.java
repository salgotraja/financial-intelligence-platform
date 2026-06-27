package dev.engnotes.watchlist.model;

/**
 * Watchlist input. {@code operation} is set by the API Gateway integration template per HTTP method
 * (POST -> ADD, GET -> LIST, DELETE -> REMOVE). {@code ticker} is null for LIST. Jackson
 * deserializes the Lambda event payload directly.
 */
public record WatchlistRequest(Operation operation, String ticker, String correlationId) {}
