package dev.engnotes.watchlist.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.engnotes.watchlist.exception.WatchlistException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LotTest {

    @Test
    void acceptsValidLot() {
        var lot = new Lot(LocalDate.of(2024, 1, 10), 10, new BigDecimal("100.50"));
        assertThat(lot.qty()).isEqualTo(10);
        assertThat(lot.price()).isEqualTo(new BigDecimal("100.50"));
    }

    @Test
    void rejectsNullBuyDate() {
        assertThatThrownBy(() -> new Lot(null, 10, new BigDecimal("100"))).isInstanceOf(WatchlistException.class);
    }

    @Test
    void rejectsZeroQty() {
        assertThatThrownBy(() -> new Lot(LocalDate.of(2024, 1, 10), 0, new BigDecimal("100")))
                .isInstanceOf(WatchlistException.class);
    }

    @Test
    void rejectsNegativeQty() {
        assertThatThrownBy(() -> new Lot(LocalDate.of(2024, 1, 10), -5, new BigDecimal("100")))
                .isInstanceOf(WatchlistException.class);
    }

    @Test
    void rejectsNullPrice() {
        assertThatThrownBy(() -> new Lot(LocalDate.of(2024, 1, 10), 10, null)).isInstanceOf(WatchlistException.class);
    }

    @Test
    void rejectsZeroPrice() {
        assertThatThrownBy(() -> new Lot(LocalDate.of(2024, 1, 10), 10, BigDecimal.ZERO))
                .isInstanceOf(WatchlistException.class);
    }

    @Test
    void rejectsNegativePrice() {
        assertThatThrownBy(() -> new Lot(LocalDate.of(2024, 1, 10), 10, new BigDecimal("-1")))
                .isInstanceOf(WatchlistException.class);
    }
}
