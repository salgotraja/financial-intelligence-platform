package dev.engnotes.insight.model;

/**
 * EventBridge trigger payload for the computeCorrelations Lambda. {@code source} mirrors the
 * envelope IngestionStack's other schedule rules use ({@code Map.of("source", "...")}), so the
 * bean's logs carry the same trigger provenance as fetchMarketData's.
 */
public record CorrelationRequest(String source) {}
