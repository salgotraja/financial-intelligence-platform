package dev.engnotes.watchlist.portfolio;

import dev.engnotes.watchlist.exception.WatchlistException;
import dev.engnotes.watchlist.model.Lot;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Date-range validation for lots against an injectable {@link Clock} (never {@code LocalDate.now()} with no arg). */
public final class PortfolioValidator {

    private static final LocalDate NSE_ELECTRONIC_ERA_FLOOR = LocalDate.of(1996, 1, 1);

    private PortfolioValidator() {}

    /** Each lot's buyDate must not be in the future (per {@code clock}) and not before the NSE electronic era floor. */
    public static void validateLots(List<Lot> lots, Clock clock) {
        LocalDate today = LocalDate.now(clock);
        for (Lot lot : lots) {
            if (lot.buyDate().isAfter(today)) {
                throw new WatchlistException(
                        "invalid request body: lot buyDate " + lot.buyDate() + " must not be after " + today);
            }
            if (lot.buyDate().isBefore(NSE_ELECTRONIC_ERA_FLOOR)) {
                throw new WatchlistException("invalid request body: lot buyDate " + lot.buyDate()
                        + " must not be before " + NSE_ELECTRONIC_ERA_FLOOR);
            }
        }
    }
}
