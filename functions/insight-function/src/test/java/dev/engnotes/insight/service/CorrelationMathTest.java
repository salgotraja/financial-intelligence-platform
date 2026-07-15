package dev.engnotes.insight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.engnotes.insight.model.TickerSeries;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CorrelationMathTest {

    @Test
    void perfectlyProportionalReturnsYieldRhoOfOne() {
        double[] x = {0.01, -0.02, 0.03, 0.01, -0.01};
        double[] y = {0.02, -0.04, 0.06, 0.02, -0.02}; // exactly 2x x

        assertThat(CorrelationMath.pearson(x, y)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void perfectlyInverseReturnsYieldRhoOfNegativeOne() {
        double[] x = {0.01, -0.02, 0.03, 0.01, -0.01};
        double[] y = {-0.01, 0.02, -0.03, -0.01, 0.01};

        assertThat(CorrelationMath.pearson(x, y)).isCloseTo(-1.0, within(1e-9));
    }

    @Test
    void zeroVarianceSeriesYieldsZeroNotNaN() {
        double[] flat = {0.01, 0.01, 0.01, 0.01};
        double[] moving = {0.01, -0.02, 0.03, 0.01};

        assertThat(CorrelationMath.pearson(flat, moving)).isZero();
    }

    @Test
    void pearsonRejectsMismatchedOrTooShortSeries() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> CorrelationMath.pearson(new double[] {1}, new double[] {1}))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> CorrelationMath.pearson(new double[] {1, 2}, new double[] {1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsComputesSimplePeriodOverPeriodChange() {
        double[] returns =
                CorrelationMath.returns(List.of(new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("99")));

        assertThat(returns).hasSize(2);
        assertThat(returns[0]).isCloseTo(0.10, within(1e-9));
        assertThat(returns[1]).isCloseTo(-0.10, within(1e-9));
    }

    @Test
    void correlateReturnsEmptyBelowMinAlignedPoints() {
        TickerSeries a = series("A", 1, 9, 1.0);
        TickerSeries b = series("B", 1, 9, 2.0);

        assertThat(CorrelationMath.correlate(a, b, 10)).isEmpty();
    }

    @Test
    void correlateAlignsOnCommonBucketsOnlyThenComputesRho() {
        // A has buckets 1..15; B has 1..10 plus 16..20 (no overlap past 10). Common = 1..10 (10 points).
        TickerSeries a = series("A", 1, 15, 1.0);
        TickerSeries b = seriesWithOffset("B", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 16, 17, 18, 19, 20), 1.0);

        var rho = CorrelationMath.correlate(a, b, 10);

        assertThat(rho).isPresent();
        assertThat(rho.orElseThrow()).isCloseTo(1.0, within(1e-9)); // both series move in lockstep
    }

    /** A monotonically rising series (bucket i -> price base+i), buckets labeled t01..t99 so they sort correctly. */
    private static TickerSeries series(String ticker, int fromInclusive, int toInclusive, double base) {
        return seriesWithOffset(
                ticker,
                java.util.stream.IntStream.rangeClosed(fromInclusive, toInclusive)
                        .boxed()
                        .toList(),
                base);
    }

    private static TickerSeries seriesWithOffset(String ticker, List<Integer> minuteOffsets, double base) {
        List<String> buckets =
                minuteOffsets.stream().map(i -> "t%02d".formatted(i)).toList();
        List<BigDecimal> prices =
                minuteOffsets.stream().map(i -> BigDecimal.valueOf(base + i)).toList();
        return new TickerSeries(ticker, buckets, prices);
    }
}
