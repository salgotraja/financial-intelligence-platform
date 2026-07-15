package dev.engnotes.insight.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * Stateless helper for the NSE intraday session window, mirroring the frontend market-hours window
 * (09:00-15:35 IST). An instant is inside market hours when, viewed in Asia/Kolkata, it falls on a
 * trading day (Monday-Friday, excluding NSE trading holidays) and the local time is within 09:00 to
 * 15:35 inclusive.
 *
 * <p>Byte-identical duplicate of {@code dev.engnotes.ingestion.service.MarketHours}: the correlations
 * Lambda needs the same holiday-aware guard but lives in a separate module (insight-function), and a
 * shared module would be a heavier coupling than copying ~60 lines. Keep both copies in sync when the
 * NSE calendar changes.
 */
public final class MarketHours {

    private static final ZoneId TRADING_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime SESSION_OPEN = LocalTime.of(9, 0);
    private static final LocalTime SESSION_CLOSE = LocalTime.of(15, 35);

    /**
     * NSE Capital Market segment trading holidays for calendar year 2026, per NSE circular
     * NSE/CMTR/71775 ("Trading holidays for the calendar year 2026", December 12, 2025):
     * https://nsearchives.nseindia.com/content/circulars/CMTR71775.pdf
     * Plus the ad-hoc January 15, 2026 holiday for the Maharashtra Municipal Corporation
     * elections, notified as a partial modification to NSE/CMTR/71775 (~January 12, 2026;
     * corroborated by NSE/CD/72233, January 9, 2026, the equivalent Currency Derivatives
     * notice: https://nsearchives.nseindia.com/content/circulars/CD72233.pdf).
     * Keep in the same chronological order as frontend/src/lib/market-hours.ts and
     * dev.engnotes.ingestion.service.MarketHours so drift between the lists is a straight visual diff.
     */
    private static final Set<LocalDate> TRADING_HOLIDAYS_2026 = Set.of(
            LocalDate.of(2026, 1, 15), // Maharashtra Municipal Corporation Elections
            LocalDate.of(2026, 1, 26), // Republic Day
            LocalDate.of(2026, 3, 3), // Holi
            LocalDate.of(2026, 3, 26), // Shri Ram Navami
            LocalDate.of(2026, 3, 31), // Shri Mahavir Jayanti
            LocalDate.of(2026, 4, 3), // Good Friday
            LocalDate.of(2026, 4, 14), // Dr. Baba Saheb Ambedkar Jayanti
            LocalDate.of(2026, 5, 1), // Maharashtra Day
            LocalDate.of(2026, 5, 28), // Bakri Id
            LocalDate.of(2026, 6, 26), // Muharram
            LocalDate.of(2026, 9, 14), // Ganesh Chaturthi
            LocalDate.of(2026, 10, 2), // Mahatma Gandhi Jayanti
            LocalDate.of(2026, 10, 20), // Dussehra
            LocalDate.of(2026, 11, 10), // Diwali-Balipratipada
            LocalDate.of(2026, 11, 24), // Prakash Gurpurb Sri Guru Nanak Dev
            LocalDate.of(2026, 12, 25) // Christmas
            );

    private MarketHours() {}

    /** True when {@code date} is an NSE trading day: a weekday that is not a listed holiday. */
    public static boolean isTradingDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        return !TRADING_HOLIDAYS_2026.contains(date);
    }

    /** True when {@code instant}, viewed in Asia/Kolkata, falls inside the NSE trading session. */
    public static boolean isMarketOpen(Instant instant) {
        ZonedDateTime local = instant.atZone(TRADING_ZONE);
        if (!isTradingDay(local.toLocalDate())) {
            return false;
        }
        LocalTime time = local.toLocalTime();
        return !time.isBefore(SESSION_OPEN) && !time.isAfter(SESSION_CLOSE);
    }
}
