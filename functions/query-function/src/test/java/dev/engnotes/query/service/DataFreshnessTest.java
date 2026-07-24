package dev.engnotes.query.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DataFreshnessTest {

    @Test
    void ageIsPositiveSecondsForPastTimestamp() {
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        Instant point = Instant.parse("2026-07-24T09:59:30Z");
        assertThat(DataFreshness.ageSeconds(point, now)).isEqualTo(30L);
    }

    @Test
    void ageFloorsAtZeroForFutureTimestamp() {
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        Instant point = Instant.parse("2026-07-24T10:00:05Z");
        assertThat(DataFreshness.ageSeconds(point, now)).isEqualTo(0L);
    }
}
