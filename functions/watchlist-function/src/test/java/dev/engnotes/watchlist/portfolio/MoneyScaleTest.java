package dev.engnotes.watchlist.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyScaleTest {

    @Test
    void toDisplayRoundsHalfUpToScaleTwo() {
        assertThat(MoneyScale.toDisplay(new BigDecimal("15.0050"))).isEqualByComparingTo(new BigDecimal("15.01"));
    }

    @Test
    void toDisplayResultHasScaleTwo() {
        assertThat(MoneyScale.toDisplay(new BigDecimal("15.0050")).scale()).isEqualTo(2);
    }

    @Test
    void internalScaleIsFour() {
        assertThat(MoneyScale.INTERNAL).isEqualTo(4);
    }

    @Test
    void displayScaleIsTwo() {
        assertThat(MoneyScale.DISPLAY).isEqualTo(2);
    }
}
