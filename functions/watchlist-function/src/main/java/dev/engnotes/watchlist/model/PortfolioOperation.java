package dev.engnotes.watchlist.model;

/**
 * Portfolio operation, set by the API Gateway integration template per HTTP method (POST -> CREATE,
 * GET -> LIST, DELETE -> DELETE, GET /portfolio/history -> HISTORY).
 */
public enum PortfolioOperation {
    CREATE,
    LIST,
    DELETE,
    HISTORY
}
