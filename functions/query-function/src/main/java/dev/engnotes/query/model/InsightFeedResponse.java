package dev.engnotes.query.model;

import java.util.List;

/**
 * Read-path output for GET /insights: the caller's watchlist insight feed, newest first, capped at
 * 25 entries. {@code found} is false only when the feed is empty (empty watchlist or no insights
 * yet), matching the sibling routes' semantics: 200 with an empty list, never 404.
 */
public record InsightFeedResponse(List<FeedInsight> insights, boolean found) {

    public InsightFeedResponse {
        insights = insights == null ? List.of() : List.copyOf(insights);
    }

    public static InsightFeedResponse empty() {
        return new InsightFeedResponse(List.of(), false);
    }
}
