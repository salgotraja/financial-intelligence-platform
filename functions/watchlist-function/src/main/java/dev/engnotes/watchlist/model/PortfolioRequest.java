package dev.engnotes.watchlist.model;

import java.util.List;

/**
 * Portfolio input. {@code operation} is set by the API Gateway integration template per HTTP method
 * (POST -> CREATE, GET -> LIST, DELETE -> DELETE). {@code ticker} is null for LIST. {@code lots} is
 * only populated for CREATE. {@code ownerSub} is the caller's Cognito {@code sub} from the
 * authorizer context ($context.authorizer.sub); it is null for unauthenticated local runs, in which
 * case the handler falls back to DEFAULT_OWNER_SUB.
 */
public record PortfolioRequest(
        PortfolioOperation operation, String ticker, List<Lot> lots, String ownerSub, String correlationId) {}
