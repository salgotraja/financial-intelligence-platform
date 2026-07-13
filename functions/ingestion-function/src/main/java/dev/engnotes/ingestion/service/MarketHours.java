package dev.engnotes.ingestion.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Stateless helper for the NSE intraday session window, mirroring the frontend market-hours window
 * (09:00-15:35 IST). No holiday calendar: an instant is in session when, viewed in Asia/Kolkata, it
 * falls on a weekday (Monday-Friday) and the local time is within 09:00 to 15:35 inclusive.
 */
public final class MarketHours {

    private static final ZoneId TRADING_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime SESSION_OPEN = LocalTime.of(9, 0);
    private static final LocalTime SESSION_CLOSE = LocalTime.of(15, 35);

    private MarketHours() {}

    public static boolean isSession(Instant instant) {
        ZonedDateTime local = instant.atZone(TRADING_ZONE);
        DayOfWeek day = local.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = local.toLocalTime();
        return !time.isBefore(SESSION_OPEN) && !time.isAfter(SESSION_CLOSE);
    }
}
