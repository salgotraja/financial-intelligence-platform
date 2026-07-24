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

    @Test
    void ageIsZeroWhenNewestEqualsNow() {
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        assertThat(DataFreshness.ageSeconds(now, now)).isEqualTo(0L);
    }

    @Test
    void ageSecondsSafeReturnsEmptyForNullTimestamp() {
        assertThat(DataFreshness.ageSecondsSafe(null, Instant.now())).isEmpty();
    }

    @Test
    void ageSecondsSafeReturnsEmptyForBlankTimestamp() {
        assertThat(DataFreshness.ageSecondsSafe("   ", Instant.now())).isEmpty();
    }

    @Test
    void ageSecondsSafeReturnsEmptyForMalformedTimestamp() {
        assertThat(DataFreshness.ageSecondsSafe("not-a-timestamp", Instant.now()))
                .isEmpty();
    }

    @Test
    void ageSecondsSafeReturnsPresentValueForValidTimestamp() {
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        assertThat(DataFreshness.ageSecondsSafe("2026-07-24T09:59:30Z", now)).hasValue(30L);
    }
}
