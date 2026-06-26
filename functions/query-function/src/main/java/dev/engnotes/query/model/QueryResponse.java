package dev.engnotes.query.model;

import java.util.List;

/**
 * Read-path output: the latest stored insight for a ticker. {@code found} is false when no insight
 * exists yet (a normal empty result, not an error), in which case the insight fields are null/empty.
 * Read-only: this is served straight from DynamoDB with no Bedrock call.
 *
 * <p>Carries the structured contract the write path produces (signal/confidence/rationale/drivers,
 * spec section 9) plus {@code source} (BEDROCK or RULE_BASED), so the read path surfaces the same
 * insight the pipeline generated. {@code insightText} mirrors the rationale for legacy consumers.
 */
public record QueryResponse(
        String ticker,
        String generatedAt,
        String signal,
        double confidence,
        String rationale,
        List<String> drivers,
        String source,
        String insightText,
        String modelId,
        boolean found) {

    public static QueryResponse notFound(String ticker) {
        return new QueryResponse(ticker, null, null, 0.0, null, List.of(), null, null, null, false);
    }
}
