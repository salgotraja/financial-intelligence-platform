package dev.engnotes.watchlist.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class WatchlistResponseTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void addedSerializesTickersAsEmptyArrayNotNull() {
        String json = mapper.writeValueAsString(WatchlistResponse.added("RELIANCE.NS"));

        assertThat(json).contains("\"tickers\":[]").doesNotContain("\"tickers\":null");
    }

    @Test
    void removedSerializesTickersAsEmptyArrayNotNull() {
        String json = mapper.writeValueAsString(WatchlistResponse.removed("TCS.NS"));

        assertThat(json).contains("\"tickers\":[]").doesNotContain("\"tickers\":null");
    }

    @Test
    void nullTickersInCanonicalConstructorNormalizesToEmptyImmutableList() {
        WatchlistResponse response = new WatchlistResponse("ok", null, null);

        assertThat(response.tickers()).isEmpty();
        assertThat(mapper.writeValueAsString(response)).contains("\"tickers\":[]");
    }

    @Test
    void listKeepsProvidedTickersAsImmutableCopy() {
        WatchlistResponse response = WatchlistResponse.list(List.of("RELIANCE.NS", "INFY.NS"));

        assertThat(response.tickers()).containsExactly("RELIANCE.NS", "INFY.NS");
        assertThat(mapper.writeValueAsString(response)).contains("\"tickers\":[\"RELIANCE.NS\",\"INFY.NS\"]");
    }
}
