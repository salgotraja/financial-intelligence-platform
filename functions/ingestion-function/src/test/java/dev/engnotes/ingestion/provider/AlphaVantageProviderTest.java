package dev.engnotes.ingestion.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AlphaVantageProviderTest {

    private static final String QUOTE = """
            {"Global Quote":{
              "01. symbol":"RELIANCE.NS",
              "05. price":"2950.5000",
              "08. previous close":"2900.0000",
              "06. volume":"1234567",
              "09. change":"50.5000",
              "10. change percent":"1.7414%"
            }}
            """;

    private HttpClient httpClient;
    private SecretsManagerClient secretsManager;
    private AlphaVantageProvider provider;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        secretsManager = mock(SecretsManagerClient.class);
        ObjectMapper objectMapper = JsonMapper.builder().build();
        provider = new AlphaVantageProvider(httpClient, objectMapper, secretsManager);
    }

    @Test
    void parsesGlobalQuoteFromRecordedFixture() throws Exception {
        stubSecret("DEMOKEY");
        stubResponse(200, "application/json", QUOTE);

        MarketDataResponse data = provider.fetch("RELIANCE.NS", "corr-1");

        assertThat(provider.name()).isEqualTo("alpha-vantage");
        assertThat(data.dataSource()).isEqualTo("alpha-vantage");
        assertThat(data.price()).isEqualByComparingTo("2950.5");
        assertThat(data.previousClose()).isEqualByComparingTo("2900.0");
        assertThat(data.change()).isEqualByComparingTo("50.5");
        assertThat(data.changePercent()).isEqualByComparingTo("1.7414");
        assertThat(data.volume()).isEqualTo(1234567L);
        // GLOBAL_QUOTE carries no 52-week range or market cap.
        assertThat(data.high52Week()).isNull();
        assertThat(data.low52Week()).isNull();
        assertThat(data.marketCap()).isNull();
    }

    @Test
    void throwsOnEmptyQuote() throws Exception {
        stubSecret("DEMOKEY");
        stubResponse(200, "application/json", "{\"Global Quote\":{}}");

        assertThatThrownBy(() -> provider.fetch("RELIANCE.NS", "corr-1"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("no quote");
    }

    @Test
    void cachesApiKeyAcrossInvocations() throws Exception {
        stubSecret("DEMOKEY");
        stubResponse(200, "application/json", QUOTE);

        provider.fetch("RELIANCE.NS", "corr-1");
        provider.fetch("TCS.NS", "corr-2");

        verify(secretsManager, times(1)).getSecretValue(any(GetSecretValueRequest.class));
    }

    @Test
    void supportsJsonSecretWithApiKeyField() throws Exception {
        stubSecret("{\"apiKey\":\"DEMOKEY\"}");
        stubResponse(200, "application/json", QUOTE);

        MarketDataResponse data = provider.fetch("RELIANCE.NS", "corr-1");

        assertThat(data.price()).isEqualByComparingTo("2950.5");
    }

    @Test
    void supportsJsonSecretWithKebabApiKeyField() throws Exception {
        stubSecret("{\"api-key\":\"DEMOKEY\"}");
        stubResponse(200, "application/json", QUOTE);

        MarketDataResponse data = provider.fetch("RELIANCE.NS", "corr-1");

        assertThat(data.price()).isEqualByComparingTo("2950.5");
    }

    @Test
    void urlEncodesApiKeyInQuery() throws Exception {
        stubSecret("a/b+c");
        stubResponse(200, "application/json", QUOTE);
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);

        provider.fetch("RELIANCE.NS", "corr-1");

        verify(httpClient).send(captor.capture(), any());
        assertThat(captor.getValue().uri().toString()).contains("apikey=a%2Fb%2Bc");
    }

    @Test
    void throwsOnEmptySecret() throws Exception {
        stubSecret("");

        assertThatThrownBy(() -> provider.fetch("RELIANCE.NS", "corr-1"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("empty");
    }

    private void stubSecret(String secretString) {
        when(secretsManager.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(GetSecretValueResponse.builder()
                        .secretString(secretString)
                        .build());
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
