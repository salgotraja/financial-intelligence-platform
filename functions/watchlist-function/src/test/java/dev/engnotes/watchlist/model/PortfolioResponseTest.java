package dev.engnotes.watchlist.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortfolioResponseTest {

    @Test
    void createdHasStatusCreatedAndEmptyHoldings() {
        PortfolioResponse response = PortfolioResponse.created("RELIANCE.NS");

        assertThat(response.status()).isEqualTo("created");
        assertThat(response.ticker()).isEqualTo("RELIANCE.NS");
        assertThat(response.holdings()).isEmpty();
    }

    @Test
    void deletedHasStatusDeletedAndEmptyHoldings() {
        PortfolioResponse response = PortfolioResponse.deleted("TCS.NS");

        assertThat(response.status()).isEqualTo("deleted");
        assertThat(response.ticker()).isEqualTo("TCS.NS");
        assertThat(response.holdings()).isEmpty();
    }

    @Test
    void listHasStatusOkNullTickerAndProvidedHoldings() {
        PortfolioHolding holding = new PortfolioHolding(
                "RELIANCE.NS", List.of(), 10L, new BigDecimal("100.50"), null, Instant.parse("2026-07-23T00:00:00Z"));

        PortfolioResponse response = PortfolioResponse.list(List.of(holding));

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.ticker()).isNull();
        assertThat(response.holdings()).containsExactly(holding);
    }

    @Test
    void nullHoldingsInCanonicalConstructorNormalizesToEmptyImmutableList() {
        PortfolioResponse response = new PortfolioResponse("ok", null, null);

        assertThat(response.holdings()).isEmpty();
    }
}
