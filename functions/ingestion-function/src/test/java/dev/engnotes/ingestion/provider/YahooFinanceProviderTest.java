package dev.engnotes.ingestion.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.engnotes.ingestion.exception.MarketDataException;
import dev.engnotes.ingestion.model.MarketDataResponse;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class YahooFinanceProviderTest {

    private static final String QUOTE = """
            {"chart":{"result":[{"meta":{
              "regularMarketPrice":2950.5,
              "chartPreviousClose":2900.0,
              "regularMarketVolume":1234567,
              "fiftyTwoWeekHigh":3100.0,
              "fiftyTwoWeekLow":2200.0,
              "marketCap":1900000000000
            }}]}}
            """;

    private HttpClient httpClient;
    private YahooFinanceProvider provider;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        ObjectMapper objectMapper = JsonMapper.builder().build();
        provider = new YahooFinanceProvider(httpClient, objectMapper);
    }

    @Test
    void parsesQuoteFromRecordedFixture() throws Exception {
        stubResponse(200, "application/json", QUOTE);

        MarketDataResponse data = provider.fetch("RELIANCE.NS", "corr-1");

        assertThat(provider.name()).isEqualTo("yahoo-finance");
        assertThat(data.dataSource()).isEqualTo("yahoo-finance");
        assertThat(data.ticker()).isEqualTo("RELIANCE.NS");
        assertThat(data.price()).isEqualByComparingTo("2950.5");
        assertThat(data.previousClose()).isEqualByComparingTo("2900.0");
        assertThat(data.change()).isEqualByComparingTo("50.5");
        assertThat(data.changePercent()).isEqualByComparingTo("1.74");
        assertThat(data.volume()).isEqualTo(1234567L);
        assertThat(data.high52Week()).isEqualByComparingTo("3100.0");
        assertThat(data.low52Week()).isEqualByComparingTo("2200.0");
        assertThat(data.correlationId()).isEqualTo("corr-1");
    }

    @Test
    void throwsOnNon200Status() throws Exception {
        stubResponse(503, "application/json", "{}");

        assertThatThrownBy(() -> provider.fetch("RELIANCE.NS", "corr-1"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("503");
    }

    @Test
    void throwsOnNonJsonContentType() throws Exception {
        stubResponse(200, "text/html", "<html>blocked</html>");

        assertThatThrownBy(() -> provider.fetch("RELIANCE.NS", "corr-1"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("non-JSON");
    }

    @Test
    void throwsWhenBodyExceedsSizeCap() throws Exception {
        stubResponse(200, "application/json", "x".repeat(1_000_001));

        assertThatThrownBy(() -> provider.fetch("RELIANCE.NS", "corr-1"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("size cap");
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
