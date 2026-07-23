package dev.engnotes.watchlist.model;

import java.util.List;

/**
 * Portfolio output. CREATE returns {@code status=created}; DELETE returns {@code status=deleted} (a
 * no-op delete of an untracked ticker is still {@code deleted}); LIST returns {@code status=ok}
 * with {@code holdings} populated. The unused fields are null/empty per operation.
 */
public record PortfolioResponse(String status, String ticker, List<PortfolioHolding> holdings) {

    public PortfolioResponse {
        holdings = holdings == null ? List.of() : List.copyOf(holdings);
    }

    public static PortfolioResponse created(String ticker) {
        return new PortfolioResponse("created", ticker, null);
    }

    public static PortfolioResponse deleted(String ticker) {
        return new PortfolioResponse("deleted", ticker, null);
    }

    public static PortfolioResponse list(List<PortfolioHolding> holdings) {
        return new PortfolioResponse("ok", null, holdings);
    }
}
