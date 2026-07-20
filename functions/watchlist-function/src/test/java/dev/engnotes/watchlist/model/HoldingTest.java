package dev.engnotes.watchlist.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.engnotes.watchlist.exception.WatchlistException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HoldingTest {

    private static Lot lot() {
        return new Lot(LocalDate.of(2024, 1, 10), 10, new BigDecimal("100"));
    }

    @Test
    void rejectsBlankTicker() {
        assertThatThrownBy(() -> new Holding("  ", List.of(lot()))).isInstanceOf(WatchlistException.class);
    }

    @Test
    void rejectsNullTicker() {
        assertThatThrownBy(() -> new Holding(null, List.of(lot()))).isInstanceOf(WatchlistException.class);
    }

    @Test
    void rejectsEmptyLots() {
        assertThatThrownBy(() -> new Holding("RELIANCE.NS", List.of())).isInstanceOf(WatchlistException.class);
    }

    @Test
    void rejectsNullLots() {
        assertThatThrownBy(() -> new Holding("RELIANCE.NS", null)).isInstanceOf(WatchlistException.class);
    }

    @Test
    void rejectsMoreThanFiftyLots() {
        List<Lot> lots = IntStream.range(0, 51).mapToObj(i -> lot()).toList();
        assertThatThrownBy(() -> new Holding("RELIANCE.NS", lots)).isInstanceOf(WatchlistException.class);
    }

    @Test
    void acceptsSingleLot() {
        var holding = new Holding("RELIANCE.NS", List.of(lot()));
        assertThat(holding.lots()).hasSize(1);
    }

    @Test
    void acceptsFiftyLots() {
        List<Lot> lots = IntStream.range(0, 50).mapToObj(i -> lot()).toList();
        var holding = new Holding("RELIANCE.NS", lots);
        assertThat(holding.lots()).hasSize(50);
    }

    @Test
    void lotsListIsDefensiveCopyIndependentOfInputMutation() {
        List<Lot> mutable = new ArrayList<>();
        mutable.add(lot());
        var holding = new Holding("RELIANCE.NS", mutable);

        mutable.add(lot());

        assertThat(holding.lots()).hasSize(1);
    }
}
