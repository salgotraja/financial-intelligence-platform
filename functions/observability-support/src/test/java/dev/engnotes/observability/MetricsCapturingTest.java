package dev.engnotes.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MetricsCapturingTest {
    @Test
    void forTestingCapturesEmittedRecords() {
        Metrics.Capture cap = Metrics.forTesting();
        cap.metrics().count("MarketDataIngested", "ticker", "RELIANCE.NS", "source", "yahoo");
        cap.metrics().count("IngestionFailure", "reason", "timeout");
        assertThat(cap.records()).hasSize(2);
        assertThat(cap.records().get(0))
                .contains("FinancialPlatform")
                .contains("MarketDataIngested")
                .contains("RELIANCE.NS")
                .contains("yahoo");
        assertThat(cap.records().get(1)).contains("IngestionFailure").contains("timeout");
    }
}
