package dev.engnotes.insight.model;

import java.util.List;

/**
 * Assembled cross-ticker context for one group insight generation: the triggering ticker and its
 * anomaly, every member's latest snapshot, and the group's pairwise correlations from
 * {@code GROUP#{groupId}/META}. Built by {@code GroupContextReader} and consumed by both the
 * Bedrock prompt builder and the rule-based cross-ticker fallback, so the two generators reason
 * over identical data.
 */
public record GroupInsightContext(
        String groupId,
        List<String> tickers,
        String triggeringTicker,
        String anomalyReason,
        List<MemberSnapshot> members,
        List<CorrelationEdge> pairwiseRhos,
        String window,
        String computedAt) {}
