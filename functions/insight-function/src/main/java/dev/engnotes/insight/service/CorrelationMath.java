package dev.engnotes.insight.service;

import dev.engnotes.insight.model.TickerSeries;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure correlation arithmetic: simple returns, Pearson's r, and the alignment step that turns two
 * independently-fetched {@link TickerSeries} into a pair of equal-length return series. No AWS
 * dependency, so every branch is testable against known series.
 */
final class CorrelationMath {

    private CorrelationMath() {}

    /** Simple period-over-period returns: {@code (p[i] - p[i-1]) / p[i-1]} for i = 1..n-1. */
    static double[] returns(List<BigDecimal> prices) {
        double[] result = new double[prices.size() - 1];
        for (int i = 1; i < prices.size(); i++) {
            BigDecimal previous = prices.get(i - 1);
            BigDecimal current = prices.get(i);
            result[i - 1] = current.subtract(previous)
                    .divide(previous, MathContext.DECIMAL64)
                    .doubleValue();
        }
        return result;
    }

    /** Pearson correlation coefficient. Zero variance in either series yields 0.0, not NaN. */
    static double pearson(double[] x, double[] y) {
        if (x.length != y.length || x.length < 2) {
            throw new IllegalArgumentException("Pearson correlation requires equal-length series of at least 2 points");
        }
        double meanX = Arrays.stream(x).average().orElseThrow();
        double meanY = Arrays.stream(y).average().orElseThrow();

        double covariance = 0;
        double varianceX = 0;
        double varianceY = 0;
        for (int i = 0; i < x.length; i++) {
            double dx = x[i] - meanX;
            double dy = y[i] - meanY;
            covariance += dx * dy;
            varianceX += dx * dx;
            varianceY += dy * dy;
        }

        if (varianceX == 0 || varianceY == 0) {
            return 0.0;
        }
        return covariance / Math.sqrt(varianceX * varianceY);
    }

    /**
     * Aligns two tickers' bucketed series on their common buckets and computes Pearson's r on the
     * resulting return series. Empty when fewer than {@code minAlignedPoints} buckets are common to
     * both (too little overlap for a meaningful correlation).
     */
    static Optional<Double> correlate(TickerSeries a, TickerSeries b, int minAlignedPoints) {
        List<String> common =
                a.buckets().stream().filter(b.buckets()::contains).sorted().toList();
        if (common.size() < minAlignedPoints) {
            return Optional.empty();
        }
        double[] returnsA = returns(alignedPrices(a, common));
        double[] returnsB = returns(alignedPrices(b, common));
        return Optional.of(pearson(returnsA, returnsB));
    }

    private static List<BigDecimal> alignedPrices(TickerSeries series, List<String> buckets) {
        Map<String, BigDecimal> byBucket = new HashMap<>();
        for (int i = 0; i < series.buckets().size(); i++) {
            byBucket.put(series.buckets().get(i), series.prices().get(i));
        }
        List<BigDecimal> aligned = new ArrayList<>(buckets.size());
        for (String bucket : buckets) {
            aligned.add(byBucket.get(bucket));
        }
        return aligned;
    }
}
