package dev.engnotes.query.service;

import java.time.Duration;
import java.time.Instant;

/** Pure helper: how many seconds old the newest served data point is, floored at zero. */
public final class DataFreshness {

    private DataFreshness() {}

    public static long ageSeconds(Instant newestPointTs, Instant now) {
        return Math.max(0L, Duration.between(newestPointTs, now).toSeconds());
    }
}
