package dev.engnotes.insight.model;

import java.util.List;

/**
 * A threshold-clustered group of correlated tickers, and the shape persisted at
 * {@code GROUP#{groupId}/META}. {@code members} is sorted ascending; {@code pairwiseRhos} carries
 * every rho computed between two members (including pairs below the clustering threshold, kept
 * transitively via a third ticker), not only the qualifying edges.
 */
public record CorrelationGroup(
        String groupId, List<String> members, List<CorrelationEdge> pairwiseRhos, String window, String computedAt) {}
