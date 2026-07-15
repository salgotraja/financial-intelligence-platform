package dev.engnotes.insight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MarketHoursTest {

    @Test
    void regularMondayIsATradingDayAndOpenMidSession() {
        LocalDate monday = LocalDate.of(2026, 7, 13);
        assertThat(MarketHours.isTradingDay(monday)).isTrue();
        assertThat(MarketHours.isMarketOpen(Instant.parse("2026-07-13T04:30:00Z")))
                .isTrue(); // 10:00 IST
    }

    @Test
    void weekendIsNotATradingDay() {
        assertThat(MarketHours.isTradingDay(LocalDate.of(2026, 7, 11))).isFalse(); // Saturday
        assertThat(MarketHours.isTradingDay(LocalDate.of(2026, 7, 12))).isFalse(); // Sunday
    }

    @Test
    void nseHolidayIsNotATradingDayEvenThoughItIsAWeekday() {
        LocalDate republicDay = LocalDate.of(2026, 1, 26); // Monday
        assertThat(republicDay.getDayOfWeek().getValue()).isLessThanOrEqualTo(5);
        assertThat(MarketHours.isTradingDay(republicDay)).isFalse();
    }

    @Test
    void marketReadsClosedOnAHolidayEvenDuringSessionHours() {
        // 2026-01-26 (Republic Day) 10:00 IST would be mid-session on a regular weekday.
        assertThat(MarketHours.isMarketOpen(Instant.parse("2026-01-26T04:30:00Z")))
                .isFalse();
    }

    @Test
    void sessionEdgesArePreservedOnATradingDay() {
        assertThat(MarketHours.isMarketOpen(Instant.parse("2026-07-13T03:30:00Z")))
                .isTrue(); // 09:00 IST open
        assertThat(MarketHours.isMarketOpen(Instant.parse("2026-07-13T03:29:00Z")))
                .isFalse(); // 08:59 IST
        assertThat(MarketHours.isMarketOpen(Instant.parse("2026-07-13T10:05:00Z")))
                .isTrue(); // 15:35 IST close
        assertThat(MarketHours.isMarketOpen(Instant.parse("2026-07-13T10:06:00Z")))
                .isFalse(); // 15:36 IST
    }
}
