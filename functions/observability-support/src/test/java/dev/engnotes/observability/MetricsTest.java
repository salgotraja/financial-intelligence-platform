package dev.engnotes.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MetricsTest {

    @Test
    void forFunctionCreatesUsableLogger() {
        Metrics metrics = Metrics.forFunction("test-fn");
        assertThat(metrics).isNotNull();
    }

    @Test
    void countAcceptsBoundedDimensions() {
        Metrics metrics = Metrics.forFunction("test-fn");
        // Should not throw for allowed dimensions.
        metrics.count("MarketDataIngested", "ticker", "RELIANCE.NS", "source", "yahoo");
        metrics.flush();
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
    void propertyIgnoresNullValue() {
        Metrics metrics = Metrics.forFunction("test-fn");
        // Null identity must be a silent no-op, not an NPE.
        metrics.property("userId", null);
        metrics.flush();
    }
}
