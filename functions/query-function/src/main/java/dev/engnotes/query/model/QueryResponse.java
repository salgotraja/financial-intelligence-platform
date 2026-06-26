package dev.engnotes.query.model;

/**
 * Read-path output: the latest stored insight for a ticker. {@code found} is false when no
 * insight exists yet (a normal empty result, not an error), in which case the insight fields
 * are null. Read-only: this is served straight from DynamoDB with no Bedrock call.
 */
public record QueryResponse(String ticker, String generatedAt, String insightText, String modelId, boolean found) {

    public static QueryResponse notFound(String ticker) {
        return new QueryResponse(ticker, null, null, null, false);
    }
}
