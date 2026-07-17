package dev.engnotes.ingestion.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.ingestion.exception.MarketDataException;
import dev.engnotes.ingestion.model.DailyBar;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class YahooHistoryProviderTest {

    // Three daily bars; the middle close is null (Yahoo emits null slots for halted days)
    // and must be skipped. Epochs are 03:45 UTC = 09:15 IST on 2026-07-14/15/16.
    private static final String HISTORY = """
            {"chart":{"result":[{
              "timestamp":[1784088900,1784175300,1784261700],
              "indicators":{"quote":[{
                "open":[100.0,101.5,null],
                "high":[102.0,103.0,106.0],
                "low":[99.0,100.5,103.5],
                "close":[101.0,null,105.0],
                "volume":[1000000,1100000,null]
              }]}
            }]}}
            """;

    private HttpClient httpClient;
    private YahooHistoryProvider provider;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        ObjectMapper objectMapper = JsonMapper.builder().build();
        provider = new YahooHistoryProvider(httpClient, objectMapper);
    }

    @Test
    void parsesDailyBarsSkippingNullCloses() throws Exception {
        stubResponse(200, "application/json", HISTORY);

        List<DailyBar> bars = provider.fetchDailyBars("INFY.NS", "corr-h1");

        assertThat(bars).hasSize(2);
        assertThat(bars.getFirst().date()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(bars.getFirst().open()).isEqualByComparingTo("100.0");
        assertThat(bars.getFirst().close()).isEqualByComparingTo("101.0");
        assertThat(bars.getFirst().volume()).isEqualTo(1_000_000L);
        assertThat(bars.getLast().date()).isEqualTo(LocalDate.of(2026, 7, 17));
        assertThat(bars.getLast().close()).isEqualByComparingTo("105.0");
        assertThat(bars.getLast().open()).isNull(); // null open survives as null, close drives inclusion
        assertThat(bars.getLast().volume()).isNull();
    }

    @Test
    void requestsOneYearOfDailyBarsWithEncodedTicker() throws Exception {
        stubResponse(200, "application/json", HISTORY);
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);

        provider.fetchDailyBars("^NSEI", "corr-h2");

        verify(httpClient).send(captor.capture(), any());
        assertThat(captor.getValue().uri().toString())
                .contains("/chart/%5ENSEI?")
                .contains("interval=1d")
                .contains("range=1y");
    }

    @Test
    void emptyResultYieldsEmptyList() throws Exception {
        stubResponse(200, "application/json", "{\"chart\":{\"result\":[{\"timestamp\":[]}]}}");

        assertThat(provider.fetchDailyBars("INFY.NS", "corr-h3")).isEmpty();
    }

    @Test
    void throwsOnNon200Status() throws Exception {
        stubResponse(429, "application/json", "{}");

        assertThatThrownBy(() -> provider.fetchDailyBars("INFY.NS", "corr-h4"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("429");
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(int status, String contentType, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers())
                .thenReturn(HttpHeaders.of(Map.of("content-type", List.of(contentType)), (a, b) -> true));
        when(response.body()).thenReturn(body);
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());
    }
}
