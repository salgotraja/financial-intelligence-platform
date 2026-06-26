package dev.engnotes.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.ingestion.exception.MarketDataException;
import dev.engnotes.ingestion.model.MarketDataResponse;
import dev.engnotes.ingestion.provider.MarketDataProvider;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketDataFetchServiceTest {

    @Mock
    private MarketDataProvider primary;

    @Mock
    private MarketDataProvider fallback;

    private MarketDataFetchService service;

    @BeforeEach
    void setUp() {
        service = new MarketDataFetchService(List.of(primary, fallback));
    }

    @Test
    void returnsPrimaryQuoteAndDoesNotTouchFallback() {
        when(primary.fetch("RELIANCE.NS", "corr-1")).thenReturn(quote("yahoo-finance"));

        MarketDataResponse data = service.fetch("RELIANCE.NS", "corr-1");

        assertThat(data.dataSource()).isEqualTo("yahoo-finance");
        verify(fallback, never()).fetch("RELIANCE.NS", "corr-1");
    }

    @Test
    void failsOverToFallbackWhenPrimaryFails() {
        when(primary.fetch("RELIANCE.NS", "corr-1")).thenThrow(new MarketDataException("yahoo blocked"));
        when(fallback.fetch("RELIANCE.NS", "corr-1")).thenReturn(quote("alpha-vantage"));

        MarketDataResponse data = service.fetch("RELIANCE.NS", "corr-1");

        assertThat(data.dataSource()).isEqualTo("alpha-vantage");
    }

    @Test
    void throwsWhenAllProvidersFail() {
        when(primary.fetch("RELIANCE.NS", "corr-1")).thenThrow(new MarketDataException("yahoo blocked"));
        when(fallback.fetch("RELIANCE.NS", "corr-1")).thenThrow(new MarketDataException("alpha throttled"));

        assertThatThrownBy(() -> service.fetch("RELIANCE.NS", "corr-1"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("All market data providers failed");
    }

    @Test
    void rejectsEmptyProviderList() {
        assertThatThrownBy(() -> new MarketDataFetchService(List.of())).isInstanceOf(IllegalStateException.class);
    }

    private static MarketDataResponse quote(String source) {
        return MarketDataResponse.builder()
                .ticker("RELIANCE.NS")
                .dataSource(source)
                .stored(false)
                .build();
    }
}
