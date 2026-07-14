package dev.engnotes.insight.model;

import java.util.List;

/**
 * The generated cross-ticker insight for a correlation group: the structured contract
 * (signal/confidence/rationale/drivers) plus {@code groupId} and {@code tickers} (spec section 9,
 * cross-ticker extension). Immutable - unlike {@link InsightResponse} there is no mutable
 * {@code stored} flag, since {@code GroupInsightStoreService} performs every write for a group
 * insight in one call and reports failure by throwing.
 */
public record GroupInsightResponse(
        String groupId,
        List<String> tickers,
        String generatedAt,
        String signal,
        double confidence,
        String rationale,
        List<String> drivers,
        String source,
        String modelId,
        String promptVersion,
        String correlationId) {}
