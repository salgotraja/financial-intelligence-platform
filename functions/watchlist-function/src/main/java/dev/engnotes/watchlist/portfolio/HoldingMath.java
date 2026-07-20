package dev.engnotes.watchlist.portfolio;

import dev.engnotes.watchlist.model.Lot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Money math over a holding's lots. */
public final class HoldingMath {

    private HoldingMath() {}

    /** Sum of quantities across all lots. */
    public static long totalQty(List<Lot> lots) {
        return lots.stream().mapToLong(Lot::qty).sum();
    }

    /**
     * Weighted average cost across all lots, at {@link MoneyScale#INTERNAL} scale, HALF_UP. The
     * division is not guaranteed to terminate (e.g. total cost 50 / total qty 3), so the scale and
     * rounding mode must be explicit.
     */
    public static BigDecimal avgCost(List<Lot> lots) {
        BigDecimal totalCost = lots.stream()
                .map(lot -> lot.price().multiply(BigDecimal.valueOf(lot.qty())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalQty = BigDecimal.valueOf(totalQty(lots));
        return totalCost.divide(totalQty, MoneyScale.INTERNAL, RoundingMode.HALF_UP);
    }
}
