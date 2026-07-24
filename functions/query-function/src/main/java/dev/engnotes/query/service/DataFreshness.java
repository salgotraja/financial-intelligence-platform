package dev.engnotes.query.service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.OptionalLong;

/** Pure helper: how many seconds old the newest served data point is, floored at zero. */
public final class DataFreshness {

    private DataFreshness() {}

    public static long ageSeconds(Instant newestPointTs, Instant now) {
        return Math.max(0L, Duration.between(newestPointTs, now).toSeconds());
    }

    /**
     * Parses the newest point's timestamp and computes its age, never throwing. Returns empty for
     * a null/blank/malformed timestamp so the caller can safely skip emitting a metric.
     */
    public static OptionalLong ageSecondsSafe(String newestPointTs, Instant now) {
        if (newestPointTs == null || newestPointTs.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(ageSeconds(Instant.parse(newestPointTs), now));
        } catch (DateTimeParseException e) {
            return OptionalLong.empty();
        }
    }
}
