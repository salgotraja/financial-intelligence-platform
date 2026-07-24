package dev.engnotes.watchlist.portfolio;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.engnotes.watchlist.exception.WatchlistException;
import dev.engnotes.watchlist.model.Lot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortfolioValidatorTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-06-15T00:00:00Z"), ZoneOffset.UTC);

    private static Lot lot(LocalDate date) {
        return new Lot(date, 10, new BigDecimal("100"));
    }

    @Test
    void rejectsBuyDateAfterToday() {
        List<Lot> lots = List.of(lot(LocalDate.of(2024, 6, 16)));
        assertThatThrownBy(() -> PortfolioValidator.validateLots(lots, FIXED_CLOCK))
                .isInstanceOf(WatchlistException.class)
                .hasMessageContaining("invalid request body");
    }

    @Test
    void rejectsPre1996Date() {
        List<Lot> lots = List.of(lot(LocalDate.of(1995, 12, 31)));
        assertThatThrownBy(() -> PortfolioValidator.validateLots(lots, FIXED_CLOCK))
                .isInstanceOf(WatchlistException.class)
                .hasMessageContaining("invalid request body");
    }

    @Test
    void acceptsToday() {
        List<Lot> lots = List.of(lot(LocalDate.of(2024, 6, 15)));
        assertThatCode(() -> PortfolioValidator.validateLots(lots, FIXED_CLOCK)).doesNotThrowAnyException();
    }

    @Test
    void acceptsValidPastDate() {
        List<Lot> lots = List.of(lot(LocalDate.of(2020, 1, 1)));
        assertThatCode(() -> PortfolioValidator.validateLots(lots, FIXED_CLOCK)).doesNotThrowAnyException();
    }

    @Test
    void acceptsFloorDate1996() {
        List<Lot> lots = List.of(lot(LocalDate.of(1996, 1, 1)));
        assertThatCode(() -> PortfolioValidator.validateLots(lots, FIXED_CLOCK)).doesNotThrowAnyException();
    }
}
