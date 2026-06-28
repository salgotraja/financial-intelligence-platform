package dev.engnotes.watchlist.model;

/**
 * Watchlist input. {@code operation} is set by the API Gateway integration template per HTTP method
 * (POST -> ADD, GET -> LIST, DELETE -> REMOVE). {@code ticker} is null for LIST. {@code ownerSub} is
 * the caller's Cognito {@code sub} from the authorizer context ($context.authorizer.sub); it is null
 * for unauthenticated local runs, in which case the handler falls back to DEFAULT_OWNER_SUB.
 */
public record WatchlistRequest(Operation operation, String ticker, String ownerSub, String correlationId) {}
