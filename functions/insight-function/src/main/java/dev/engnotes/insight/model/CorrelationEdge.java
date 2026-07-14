package dev.engnotes.insight.model;

import java.util.Objects;

/**
 * A computed Pearson correlation between two tickers' return series. Tickers are stored in
 * lexicographic order regardless of construction order, so {@code new CorrelationEdge("B","A",r)}
 * and {@code new CorrelationEdge("A","B",r)} are the same edge (relevant for de-duplication and
 * for deterministic test assertions).
 */
public record CorrelationEdge(String tickerA, String tickerB, double rho) {

    public CorrelationEdge {
        Objects.requireNonNull(tickerA, "tickerA");
        Objects.requireNonNull(tickerB, "tickerB");
        if (tickerA.compareTo(tickerB) > 0) {
            String swap = tickerA;
            tickerA = tickerB;
            tickerB = swap;
        }
    }
}
