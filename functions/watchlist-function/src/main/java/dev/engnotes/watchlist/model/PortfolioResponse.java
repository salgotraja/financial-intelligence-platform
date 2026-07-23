package dev.engnotes.watchlist.model;

/**
 * Portfolio output. CREATE returns {@code status=created}; DELETE returns {@code status=deleted} (a
 * no-op delete of an untracked ticker is still {@code deleted}); LIST returns {@code status=ok}
 * with {@code portfolio} populated as a priced valuation, not raw holdings. The unused fields are
 * null per operation.
 */
public record PortfolioResponse(String status, String ticker, PortfolioValuation portfolio) {

    public static PortfolioResponse created(String ticker) {
        return new PortfolioResponse("created", ticker, null);
    }

    public static PortfolioResponse deleted(String ticker) {
        return new PortfolioResponse("deleted", ticker, null);
    }

    public static PortfolioResponse list(PortfolioValuation portfolio) {
        return new PortfolioResponse("ok", null, portfolio);
    }
}
