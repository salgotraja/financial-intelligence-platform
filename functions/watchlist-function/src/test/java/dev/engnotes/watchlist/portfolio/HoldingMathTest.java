package dev.engnotes.watchlist.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.watchlist.model.Lot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class HoldingMathTest {

    private static Lot lot(int qty, String price) {
        return new Lot(LocalDate.of(2024, 1, 10), qty, new BigDecimal(price));
    }

    @Test
    void totalQtySumsAcrossLots() {
        List<Lot> lots = List.of(lot(10, "100"), lot(5, "200"), lot(3, "50"));
        assertThat(HoldingMath.totalQty(lots)).isEqualTo(18L);
    }

    @Test
    void avgCostSimpleCase() {
        List<Lot> lots = List.of(lot(100, "10"), lot(100, "20"));
        assertThat(HoldingMath.avgCost(lots)).isEqualByComparingTo(new BigDecimal("15.0000"));
    }

    @Test
    void avgCostHandlesNonTerminatingQuotientWithoutThrowing() {
        // sum = 1*10 + 2*20 = 50, totalQty = 3 -> 50/3 = 16.6666... repeating.
        List<Lot> lots = List.of(lot(1, "10"), lot(2, "20"));
        BigDecimal avg = HoldingMath.avgCost(lots);
        assertThat(avg).isEqualByComparingTo(new BigDecimal("16.6667"));
    }

    @Test
    void avgCostResultHasScaleFour() {
        List<Lot> lots = List.of(lot(1, "10"), lot(2, "20"));
        assertThat(HoldingMath.avgCost(lots).scale()).isEqualTo(4);
    }

    @Test
    void avgCostSingleLotEqualsItsPriceAtScaleFour() {
        List<Lot> lots = List.of(lot(7, "42.5"));
        assertThat(HoldingMath.avgCost(lots)).isEqualByComparingTo(new BigDecimal("42.5000"));
    }
}
