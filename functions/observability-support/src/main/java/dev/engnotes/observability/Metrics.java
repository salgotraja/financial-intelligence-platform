package dev.engnotes.observability;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import software.amazon.cloudwatchlogs.emf.logger.MetricsLogger;
import software.amazon.cloudwatchlogs.emf.model.DimensionSet;
import software.amazon.cloudwatchlogs.emf.model.Unit;

/**
 * Thin, misuse-resistant facade over aws-embedded-metrics. One instance per Lambda invocation.
 * Each {@code count}/{@code duration}/{@code gauge} call emits a single, self-contained EMF
 * record: a fresh {@link MetricsLogger} is created, stamped with namespace, function, and
 * instance properties, given only that call's dimensions, and flushed immediately. This avoids
 * cross-contamination between metrics emitted with different dimensions from the same instance -
 * in CloudWatch EMF, all dimension sets in a context apply to every metric in that context.
 * High-cardinality identity keys are rejected as dimensions - they belong in properties or logs,
 * never in a metric dimension.
 */
public final class Metrics {

    private static final String NAMESPACE = "FinancialPlatform";
    private static final Set<String> FORBIDDEN_DIMENSIONS = Set.of("userid", "user", "email", "sub");

    private final String function;
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final Supplier<MetricsLogger> loggerFactory;

    private Metrics(String function, Supplier<MetricsLogger> loggerFactory) {
        this.function = function;
        this.loggerFactory = loggerFactory;
    }

    public static Metrics forFunction(String function) {
        return forFunction(function, MetricsLogger::new);
    }

    static Metrics forFunction(String function, Supplier<MetricsLogger> loggerFactory) {
        return new Metrics(function, loggerFactory);
    }

    public Metrics count(String name, String... dimensionPairs) {
        return emit(name, 1.0, Unit.COUNT, dimensionPairs);
    }

    public Metrics count(String name, double value, String... dimensionPairs) {
        return emit(name, value, Unit.COUNT, dimensionPairs);
    }

    public Metrics duration(String name, double millis, String... dimensionPairs) {
        return emit(name, millis, Unit.MILLISECONDS, dimensionPairs);
    }

    public Metrics gauge(String name, double value, Unit unit, String... dimensionPairs) {
        return emit(name, value, unit, dimensionPairs);
    }

    public Metrics property(String key, String value) {
        if (value != null) {
            properties.put(key, value);
        }
        return this;
    }

    private Metrics emit(String name, double value, Unit unit, String... dimensionPairs) {
        DimensionSet dimensions = buildDimensions(dimensionPairs);
        MetricsLogger logger = loggerFactory.get();
        logger.setNamespace(NAMESPACE);
        logger.putProperty("function", function);
        properties.forEach(logger::putProperty);
        if (dimensions != null) {
            logger.putDimensions(dimensions);
        }
        logger.putMetric(name, value, unit);
        logger.flush();
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
            if (key == null) {
                throw new IllegalArgumentException("Dimension key must not be null");
            }
            if (FORBIDDEN_DIMENSIONS.contains(key.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "High-cardinality dimension not allowed: " + key + " (use property() instead)");
            }
            dimensions.addDimension(key, pairs[i + 1]);
        }
        return dimensions;
    }
}
