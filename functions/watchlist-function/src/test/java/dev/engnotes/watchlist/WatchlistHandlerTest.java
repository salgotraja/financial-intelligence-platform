package dev.engnotes.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
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

    @Mock
    private WatchlistStoreService store;

    @Test
    void addValidatesAndDelegates() {
        WatchlistResponse response = new WatchlistHandler()
                .watchlist(store)
                .apply(new WatchlistRequest(Operation.ADD, "RELIANCE.NS", "corr-1"));

        verify(store).add("RELIANCE.NS");
        assertThat(response.status()).isEqualTo("added");
        assertThat(response.ticker()).isEqualTo("RELIANCE.NS");
    }

    @Test
    void removeValidatesAndDelegates() {
        WatchlistResponse response = new WatchlistHandler()
                .watchlist(store)
                .apply(new WatchlistRequest(Operation.REMOVE, "TCS.NS", "corr-2"));

        verify(store).remove("TCS.NS");
        assertThat(response.status()).isEqualTo("removed");
        assertThat(response.ticker()).isEqualTo("TCS.NS");
    }

    @Test
    void listReturnsTickers() {
        when(store.list()).thenReturn(List.of("RELIANCE.NS", "INFY.NS"));

        WatchlistResponse response =
                new WatchlistHandler().watchlist(store).apply(new WatchlistRequest(Operation.LIST, null, "corr-3"));

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.tickers()).containsExactly("RELIANCE.NS", "INFY.NS");
    }

    @Test
    void addRejectsInvalidTicker() {
        assertThatThrownBy(() -> new WatchlistHandler()
                        .watchlist(store)
                        .apply(new WatchlistRequest(Operation.ADD, "bad/ticker", "corr-4")))
                .isInstanceOf(WatchlistException.class);
    }
}
