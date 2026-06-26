package dev.engnotes.ingestion.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class RunningStatsTest {

    @Test
    void emptyBaselineHasZeroStats() {
        RunningStats stats = RunningStats.empty();

        assertThat(stats.count()).isZero();
        assertThat(stats.mean()).isZero();
        assertThat(stats.variance()).isZero();
        assertThat(stats.stdDev()).isZero();
        assertThat(stats.zScore(100.0)).isZero();
    }

    @Test
    void acceptComputesMeanAndSampleVariance() {
        // Classic Welford check set: [2,4,4,4,5,5,7,9], mean 5, sum of squared deviations 32.
        double[] series = {2, 4, 4, 4, 5, 5, 7, 9};
        RunningStats stats = RunningStats.empty();
        for (double x : series) {
            stats = stats.accept(x);
        }

        assertThat(stats.count()).isEqualTo(8);
        assertThat(stats.mean()).isCloseTo(5.0, within(1e-9));
        assertThat(stats.m2()).isCloseTo(32.0, within(1e-9));
        assertThat(stats.variance()).isCloseTo(32.0 / 7.0, within(1e-9)); // sample variance
    }

    @Test
    void zScoreMeasuresDeviationInStdDevs() {
        // [10,12,14,16,18]: mean 14, sample variance 10, std dev sqrt(10).
        RunningStats stats = RunningStats.empty();
        for (double x : new double[] {10, 12, 14, 16, 18}) {
            stats = stats.accept(x);
        }

        assertThat(stats.mean()).isCloseTo(14.0, within(1e-9));
        assertThat(stats.variance()).isCloseTo(10.0, within(1e-9));
        assertThat(stats.zScore(20.0)).isCloseTo((20.0 - 14.0) / Math.sqrt(10.0), within(1e-9));
    }

    @Test
    void flatSeriesNeverProducesAnomalousZScore() {
        RunningStats stats = RunningStats.empty();
        for (int i = 0; i < 10; i++) {
            stats = stats.accept(50.0);
        }

        assertThat(stats.stdDev()).isZero();
        assertThat(stats.zScore(50.0)).isZero();
        assertThat(stats.zScore(9999.0)).isZero();
    }
}
