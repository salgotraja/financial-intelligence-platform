package dev.engnotes.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import software.amazon.cloudwatchlogs.emf.logger.MetricsLogger;
import software.amazon.cloudwatchlogs.emf.model.Unit;

class MetricsTest {

    @Test
    void forFunctionCreatesUsableLogger() {
        Metrics metrics = Metrics.forFunction("test-fn");
        assertThat(metrics).isNotNull();
    }

    @Test
    void countAcceptsBoundedDimensionsAndPinsEmfShape() throws Exception {
        CapturingEnvironment environment = new CapturingEnvironment();
        Metrics metrics = Metrics.forFunction("test-fn", () -> new MetricsLogger(environment));

        metrics.count("MarketDataIngested", "ticker", "RELIANCE.NS", "source", "yahoo");

        String json = environment.serializedRecord();
        assertThat(json).contains("\"FinancialPlatform\"");
        assertThat(json).contains("\"MarketDataIngested\"");
        assertThat(json).contains("\"ticker\":\"RELIANCE.NS\"");
        assertThat(json).contains("\"source\":\"yahoo\"");
    }

    @Test
    void countWithExplicitValueEmitsValueAndCountUnit() throws Exception {
        CapturingEnvironment environment = new CapturingEnvironment();
        Metrics metrics = Metrics.forFunction("test-fn", () -> new MetricsLogger(environment));

        metrics.count("RetryCount", 3.0, "source", "yahoo");

        String json = environment.serializedRecord();
        assertThat(json).contains("\"RetryCount\"");
        assertThat(json).contains("\"Count\"");
    }

    @Test
    void durationEmitsMillisecondsUnit() throws Exception {
        CapturingEnvironment environment = new CapturingEnvironment();
        Metrics metrics = Metrics.forFunction("test-fn", () -> new MetricsLogger(environment));

        metrics.duration("FetchLatency", 123.4, "source", "yahoo");

        String json = environment.serializedRecord();
        assertThat(json).contains("\"FetchLatency\"");
        assertThat(json).contains("\"Milliseconds\"");
    }

    @Test
    void gaugeEmitsRequestedUnit() throws Exception {
        CapturingEnvironment environment = new CapturingEnvironment();
        Metrics metrics = Metrics.forFunction("test-fn", () -> new MetricsLogger(environment));

        metrics.gauge("QueueDepth", 42.0, Unit.COUNT, "source", "yahoo");

        String json = environment.serializedRecord();
        assertThat(json).contains("\"QueueDepth\"");
        assertThat(json).contains("42.0");
    }

    @Test
    void eachEmissionIsIsolatedFromPriorDimensions() throws Exception {
        CapturingEnvironment environment = new CapturingEnvironment();
        Metrics metrics = Metrics.forFunction("test-fn", () -> new MetricsLogger(environment));

        metrics.count("First", "ticker", "RELIANCE.NS");
        String firstJson = environment.serializedRecord();

        metrics.count("Second", "source", "yahoo");
        String secondJson = environment.serializedRecord();

        assertThat(firstJson).doesNotContain("\"source\"");
        assertThat(secondJson).doesNotContain("\"ticker\"");
    }

    @Test
    void countRejectsUserIdAsDimension() {
        Metrics metrics = Metrics.forFunction("test-fn");
        assertThatThrownBy(() -> metrics.count("AuthDenied", "userId", "abc-123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void countRejectsOddDimensionPairs() {
        Metrics metrics = Metrics.forFunction("test-fn");
        assertThatThrownBy(() -> metrics.count("X", "onlyKey")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countRejectsNullDimensionKey() {
        Metrics metrics = Metrics.forFunction("test-fn");
        assertThatThrownBy(() -> metrics.count("X", (String) null, "value"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void propertyIgnoresNullValue() {
        CapturingEnvironment environment = new CapturingEnvironment();
        Metrics metrics = Metrics.forFunction("test-fn", () -> new MetricsLogger(environment));
        // Null identity must be a silent no-op, not an NPE.
        metrics.property("userId", null);
        metrics.count("NoOpCheck");
    }
}
