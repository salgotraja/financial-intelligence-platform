package dev.engnotes.ingestion.model;

/**
 * Running mean and variance via Welford's online algorithm, persisted in the per-ticker
 * {@code BASELINE} item and used for z-score anomaly detection (spec section 6).
 *
 * <p>Welford keeps {@code mean} and {@code m2} (the sum of squared deviations) incrementally, so a
 * new observation updates the baseline in O(1) without storing the full series. {@link #variance()}
 * is the sample variance (divides by {@code count - 1}); it is 0 until at least two observations
 * exist, and {@link #zScore(double)} returns 0 when the standard deviation is 0, so a flat series
 * never produces a spurious anomaly.
 */
public record RunningStats(long count, double mean, double m2) {

    public static RunningStats empty() {
        return new RunningStats(0L, 0.0, 0.0);
    }

    /** Returns a new baseline that incorporates {@code value} (does not mutate this one). */
    public RunningStats accept(double value) {
        long newCount = count + 1;
        double delta = value - mean;
        double newMean = mean + delta / newCount;
        double delta2 = value - newMean;
        return new RunningStats(newCount, newMean, m2 + delta * delta2);
    }

    public double variance() {
        return count > 1 ? m2 / (count - 1) : 0.0;
    }

    public double stdDev() {
        return Math.sqrt(variance());
    }

    /** Z-score of {@code value} against this baseline, or 0 when the standard deviation is 0. */
    public double zScore(double value) {
        double sd = stdDev();
        return sd > 0 ? (value - mean) / sd : 0.0;
    }
}
