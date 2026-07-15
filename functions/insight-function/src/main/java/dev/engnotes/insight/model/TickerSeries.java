package dev.engnotes.insight.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * A ticker's recent priced observations, bucketed to the minute and sorted in ascending
 * chronological order: {@code buckets.get(i)} is the minute-truncated ISO-8601 instant at which
 * {@code prices.get(i)} was observed.
 */
public record TickerSeries(String ticker, List<String> buckets, List<BigDecimal> prices) {

    public TickerSeries {
        if (buckets.size() != prices.size()) {
            throw new IllegalArgumentException("buckets and prices must be the same length");
        }
    }
}
