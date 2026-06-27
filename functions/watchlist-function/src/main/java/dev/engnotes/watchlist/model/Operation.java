package dev.engnotes.watchlist.model;

/** Watchlist operation, set by the API Gateway integration template per HTTP method. */
public enum Operation {
    ADD,
    LIST,
    REMOVE
}
