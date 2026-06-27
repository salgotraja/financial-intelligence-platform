package dev.engnotes.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.engnotes.watchlist.exception.WatchlistException;
import dev.engnotes.watchlist.model.Operation;
import dev.engnotes.watchlist.model.WatchlistRequest;
import dev.engnotes.watchlist.model.WatchlistResponse;
import dev.engnotes.watchlist.service.WatchlistStoreService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WatchlistHandlerTest {

    private static final String DEFAULT_SUB = "dev-user";

    @Mock
    private WatchlistStoreService store;

    @Test
    void addUsesTheRequestSubWhenPresent() {
        WatchlistResponse response = new WatchlistHandler()
                .watchlist(store, DEFAULT_SUB)
                .apply(new WatchlistRequest(Operation.ADD, "RELIANCE.NS", "user-123", "corr-1"));

        verify(store).add("user-123", "RELIANCE.NS");
        assertThat(response.status()).isEqualTo("added");
        assertThat(response.ticker()).isEqualTo("RELIANCE.NS");
    }

    @Test
    void addFallsBackToDefaultSubWhenRequestSubBlank() {
        new WatchlistHandler()
                .watchlist(store, DEFAULT_SUB)
                .apply(new WatchlistRequest(Operation.ADD, "RELIANCE.NS", null, "corr-1"));

        verify(store).add(DEFAULT_SUB, "RELIANCE.NS");
    }

    @Test
    void removeUsesTheRequestSub() {
        WatchlistResponse response = new WatchlistHandler()
                .watchlist(store, DEFAULT_SUB)
                .apply(new WatchlistRequest(Operation.REMOVE, "TCS.NS", "user-123", "corr-2"));

        verify(store).remove("user-123", "TCS.NS");
        assertThat(response.status()).isEqualTo("removed");
    }

    @Test
    void listUsesTheRequestSubAndReturnsTickers() {
        when(store.list("user-123")).thenReturn(List.of("RELIANCE.NS", "INFY.NS"));

        WatchlistResponse response = new WatchlistHandler()
                .watchlist(store, DEFAULT_SUB)
                .apply(new WatchlistRequest(Operation.LIST, null, "user-123", "corr-3"));

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.tickers()).containsExactly("RELIANCE.NS", "INFY.NS");
    }

    @Test
    void addRejectsInvalidTickerWithoutTouchingTheStore() {
        assertThatThrownBy(() -> new WatchlistHandler()
                        .watchlist(store, DEFAULT_SUB)
                        .apply(new WatchlistRequest(Operation.ADD, "bad/ticker", "user-123", "corr-4")))
                .isInstanceOf(WatchlistException.class);
        verifyNoInteractions(store);
    }
}
