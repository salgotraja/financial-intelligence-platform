package dev.engnotes.watchlist.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Scale conventions for money math: {@link #INTERNAL} for computation, {@link #DISPLAY} at the API boundary. */
public final class MoneyScale {

    public static final int INTERNAL = 4;
    public static final int DISPLAY = 2;

    private MoneyScale() {}

    /** Rounds to {@link #DISPLAY} scale using HALF_UP, for values crossing the API boundary. */
    public static BigDecimal toDisplay(BigDecimal value) {
        return value.setScale(DISPLAY, RoundingMode.HALF_UP);
    }
}
