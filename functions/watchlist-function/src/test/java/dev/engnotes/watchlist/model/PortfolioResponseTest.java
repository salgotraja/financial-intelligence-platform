package dev.engnotes.watchlist.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortfolioResponseTest {

    @Test
    void createdHasStatusCreatedAndNullPortfolio() {
        PortfolioResponse response = PortfolioResponse.created("RELIANCE.NS");

        assertThat(response.status()).isEqualTo("created");
        assertThat(response.ticker()).isEqualTo("RELIANCE.NS");
        assertThat(response.portfolio()).isNull();
        assertThat(response.history()).isNull();
    }

    @Test
    void deletedHasStatusDeletedAndNullPortfolio() {
        PortfolioResponse response = PortfolioResponse.deleted("TCS.NS");

        assertThat(response.status()).isEqualTo("deleted");
        assertThat(response.ticker()).isEqualTo("TCS.NS");
        assertThat(response.portfolio()).isNull();
        assertThat(response.history()).isNull();
    }

    @Test
    void listHasStatusOkNullTickerAndProvidedPortfolio() {
        PortfolioValuation valuation = new PortfolioValuation(
                "2026-07-23T00:00:00Z",
                new BigDecimal("1200.00"),
                new BigDecimal("1000.00"),
                new BigDecimal("200.00"),
                new BigDecimal("80.00"),
                List.of());

        PortfolioResponse response = PortfolioResponse.list(valuation);

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.ticker()).isNull();
        assertThat(response.portfolio()).isSameAs(valuation);
        assertThat(response.history()).isNull();
    }

    @Test
    void historyHasStatusOkNullTickerAndPortfolioAndProvidedHistory() {
        PortfolioHistory history = new PortfolioHistory("2026-07-20", "2026-07-23", List.of(), List.of(), List.of());

        PortfolioResponse response = PortfolioResponse.history(history);

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.ticker()).isNull();
        assertThat(response.portfolio()).isNull();
        assertThat(response.history()).isSameAs(history);
    }
}
