package dev.engnotes.observability;

import java.util.Locale;
import java.util.Set;
import software.amazon.cloudwatchlogs.emf.logger.MetricsLogger;
import software.amazon.cloudwatchlogs.emf.model.DimensionSet;
import software.amazon.cloudwatchlogs.emf.model.Unit;

/**
 * Thin, misuse-resistant facade over aws-embedded-metrics. One instance per Lambda invocation;
 * call {@link #flush()} before the handler returns. High-cardinality identity keys are rejected as
 * dimensions - they belong in properties or logs, never in a metric dimension.
 */
public final class Metrics {

    private static final String NAMESPACE = "FinancialPlatform";
    private static final Set<String> FORBIDDEN_DIMENSIONS = Set.of("userid", "user", "email", "sub");

    private final MetricsLogger logger;

    private Metrics(MetricsLogger logger, String function) {
        this.logger = logger;
        this.logger.setNamespace(NAMESPACE);
        this.logger.putProperty("function", function);
    }

    public static Metrics forFunction(String function) {
        return new Metrics(new MetricsLogger(), function);
    }

    public Metrics count(String name, String... dimensionPairs) {
        return putMetric(name, 1.0, Unit.COUNT, dimensionPairs);
    }

    public Metrics count(String name, double value, String... dimensionPairs) {
        return putMetric(name, value, Unit.COUNT, dimensionPairs);
    }

    public Metrics duration(String name, double millis, String... dimensionPairs) {
        return putMetric(name, millis, Unit.MILLISECONDS, dimensionPairs);
    }

    public Metrics gauge(String name, double value, Unit unit, String... dimensionPairs) {
        return putMetric(name, value, unit, dimensionPairs);
    }

    public Metrics property(String key, String value) {
        if (value != null) {
            logger.putProperty(key, value);
        }
        return this;
    }

    public void flush() {
        logger.flush();
    }

    private Metrics putMetric(String name, double value, Unit unit, String... dimensionPairs) {
        DimensionSet dimensions = buildDimensions(dimensionPairs);
        if (dimensions != null) {
            logger.putDimensions(dimensions);
        }
        logger.putMetric(name, value, unit);
        return this;
    }

    private static DimensionSet buildDimensions(String... pairs) {
        if (pairs == null || pairs.length == 0) {
            return null;
        }
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Dimension pairs must be key,value,... (even length)");
        }
        DimensionSet dimensions = new DimensionSet();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = pairs[i];
            if (FORBIDDEN_DIMENSIONS.contains(key.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "High-cardinality dimension not allowed: " + key + " (use property() instead)");
            }
            dimensions.addDimension(key, pairs[i + 1]);
        }
        return dimensions;
    }
}
