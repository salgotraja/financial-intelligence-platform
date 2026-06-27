package dev.engnotes.ingestion.provider;

import dev.engnotes.ingestion.exception.MarketDataException;
import dev.engnotes.ingestion.model.MarketDataResponse;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Failover market-data provider: Alpha Vantage GLOBAL_QUOTE (spec section 8).
 *
 * <p>Used when Yahoo fails or throttles. The API key lives in Secrets Manager (never an env var) and
 * is cached for the life of the Lambda instance, so only the first invocation after a cold start
 * pays the Secrets Manager call. GLOBAL_QUOTE carries price, previous close, change, and volume;
 * 52-week range and market cap are absent and left null (null-safe downstream).
 */
@Component
@Order(2)
public class AlphaVantageProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(AlphaVantageProvider.class);

    private static final String NAME = "alpha-vantage";
    private static final String URL = "https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=%s&apikey=%s";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final java.net.http.HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SecretsManagerClient secretsManager;

    @Value("${market.data.api.secret:financial-platform/market-data-api-key}")
    private String apiKeySecretName;

    // Cached per Lambda instance; resolved lazily on first use.
    private volatile String cachedApiKey;

    public AlphaVantageProvider(
            java.net.http.HttpClient httpClient, ObjectMapper objectMapper, SecretsManagerClient secretsManager) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.secretsManager = secretsManager;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public MarketDataResponse fetch(String ticker, String correlationId) {
        String url = String.format(
                URL,
                URLEncoder.encode(ticker, StandardCharsets.UTF_8),
                URLEncoder.encode(apiKey(), StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "financial-intelligence-platform/1.0")
                .header("X-Correlation-Id", correlationId)
                .GET()
                .build();

        String body = ProviderHttp.sendForJson(httpClient, request, NAME, ticker);
        MarketDataResponse data = parse(body, ticker, correlationId);
        log.info("Alpha Vantage fetch complete. ticker={} correlationId={}", ticker, correlationId);
        return data;
    }

    private MarketDataResponse parse(String json, String ticker, String correlationId) {
        try {
            JsonNode quote = objectMapper.readTree(json).path("Global Quote");
            if (quote.isMissingNode() || quote.isEmpty()) {
                // Empty quote means rate-limit note or unknown symbol; let the orchestrator decide.
                throw new MarketDataException("Alpha Vantage returned no quote for ticker " + ticker);
            }

            BigDecimal price = safeDecimal(quote, "05. price");
            BigDecimal previousClose = safeDecimal(quote, "08. previous close");
            BigDecimal change = safeDecimal(quote, "09. change");
            BigDecimal changePercent = parsePercent(quote.path("10. change percent"));

            return MarketDataResponse.builder()
                    .ticker(ticker)
                    .price(price)
                    .previousClose(previousClose)
                    .change(change)
                    .changePercent(changePercent)
                    .volume(safeLong(quote, "06. volume"))
                    .marketCap(null)
                    .high52Week(null)
                    .low52Week(null)
                    .correlationId(correlationId)
                    .dataSource(NAME)
                    .stored(false)
                    .build();

        } catch (MarketDataException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketDataException("Failed to parse Alpha Vantage response for ticker " + ticker, e);
        }
    }

    private String apiKey() {
        String key = cachedApiKey;
        if (key == null) {
            String secret = secretsManager
                    .getSecretValue(GetSecretValueRequest.builder()
                            .secretId(apiKeySecretName)
                            .build())
                    .secretString();
            key = extractKey(secret);
            cachedApiKey = key;
        }
        return key;
    }

    /**
     * Supports a raw secret string or a JSON secret carrying the key under {@code api-key} (the
     * Secrets Manager schema this platform provisions) or {@code apiKey}.
     */
    private String extractKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new MarketDataException("Alpha Vantage API key secret is empty");
        }
        try {
            JsonNode node = objectMapper.readTree(secret);
            if (node.isObject()) {
                for (String field : new String[] {"api-key", "apiKey"}) {
                    if (node.hasNonNull(field)) {
                        return node.get(field).asText().strip();
                    }
                }
            }
        } catch (Exception ignored) {
            // Not JSON; treat the secret as the raw key.
        }
        return secret.strip();
    }

    private BigDecimal parsePercent(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String raw = node.asText("").replace("%", "").strip();
        return raw.isEmpty() ? null : new BigDecimal(raw);
    }

    private BigDecimal safeDecimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value != null && !value.isNull() && !value.asText("").isBlank())
                ? new BigDecimal(value.asText())
                : null;
    }

    private Long safeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value != null && !value.isNull() && !value.asText("").isBlank()) ? value.asLong() : null;
    }
}
