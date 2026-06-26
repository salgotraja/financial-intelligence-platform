package dev.engnotes.ingestion.service;

import dev.engnotes.ingestion.exception.MarketDataException;
import dev.engnotes.ingestion.model.MarketDataResponse;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fetches live market data from Yahoo Finance public API.
 *
 * Why Yahoo Finance: no API key required for basic quotes, reliable uptime,
 * covers NSE/BSE tickers with .NS and .BO suffixes.
 *
 * For production at scale use a paid provider:
 *   - Refinitiv (Reuters) - institutional grade, expensive
 *   - Polygon.io - good value, strong REST and WebSocket APIs
 *   - Alpha Vantage - cheap, rate-limited, adequate for low volume
 *
 * API key stored in Secrets Manager, not environment variables.
 * Why: environment variables appear in CloudTrail and Lambda console logs.
 * Secrets Manager access is auditable, rotatable, and encrypted with KMS.
 */
@Service
public class MarketDataFetchService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataFetchService.class);

    private static final String YAHOO_FINANCE_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=1m&range=1d";

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SecretsManagerClient secretsManager;

    @Value("${market.data.api.secret:financial-platform/market-data-api-key}")
    private String apiKeySecretName;

    public MarketDataFetchService(SecretsManagerClient secretsManager, ObjectMapper objectMapper) {
        this.secretsManager = secretsManager;
        this.objectMapper = objectMapper;
        // Single HttpClient per Lambda instance - reused across invocations
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    /**
     * Fetches current market data for the given ticker.
     *
     * Ticker format:
     *   NSE stocks: RELIANCE.NS, TCS.NS, INFY.NS
     *   BSE stocks: RELIANCE.BO, TCS.BO
     *   Indices:    ^NSEI (Nifty 50), ^BSESN (Sensex)
     */
    public MarketDataResponse fetch(String ticker, String correlationId) {
        long startMs = System.currentTimeMillis();

        try {
            String url = String.format(YAHOO_FINANCE_URL, ticker);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("User-Agent", "financial-intelligence-platform/1.0")
                    // Correlation ID propagated to external requests for debugging
                    .header("X-Correlation-Id", correlationId)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new MarketDataException(
                        "Market data API returned status " + response.statusCode() + " for ticker " + ticker);
            }

            MarketDataResponse marketData = parseYahooResponse(response.body(), ticker, correlationId);

            long latencyMs = System.currentTimeMillis() - startMs;
            log.info(
                    "Market data fetch complete. ticker={} latencyMs={} correlationId={}",
                    ticker,
                    latencyMs,
                    correlationId);

            return marketData;

        } catch (MarketDataException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketDataException("Failed to fetch market data for ticker " + ticker, e);
        }
    }

    /**
     * Parses Yahoo Finance v8 chart API response.
     *
     * The Yahoo Finance response structure is nested and changes occasionally.
     * Wrap all field access in null checks - a missing field should produce
     * a partial response, not a NullPointerException that fails the pipeline.
     */
    private MarketDataResponse parseYahooResponse(String json, String ticker, String correlationId) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode result = root.path("chart").path("result").get(0);
            JsonNode meta = result.path("meta");

            BigDecimal currentPrice = safeDecimal(meta, "regularMarketPrice");
            BigDecimal previousClose = safeDecimal(meta, "chartPreviousClose");
            BigDecimal change =
                    currentPrice != null && previousClose != null ? currentPrice.subtract(previousClose) : null;
            BigDecimal changePercent =
                    change != null && previousClose != null && previousClose.compareTo(BigDecimal.ZERO) != 0
                            ? change.divide(previousClose, 4, java.math.RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100))
                            : null;

            return MarketDataResponse.builder()
                    .ticker(ticker)
                    .price(currentPrice)
                    .previousClose(previousClose)
                    .change(change)
                    .changePercent(changePercent)
                    .volume(safeLong(meta, "regularMarketVolume"))
                    .marketCap(safeDecimal(meta, "marketCap"))
                    .high52Week(safeDecimal(meta, "fiftyTwoWeekHigh"))
                    .low52Week(safeDecimal(meta, "fiftyTwoWeekLow"))
                    .correlationId(correlationId)
                    .dataSource("yahoo-finance")
                    .stored(false) // storeService sets this to true after persistence
                    .build();

        } catch (Exception e) {
            throw new MarketDataException("Failed to parse Yahoo Finance response for ticker " + ticker, e);
        }
    }

    private BigDecimal safeDecimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value != null && !value.isNull()) ? new BigDecimal(value.asText()) : null;
    }

    private Long safeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value != null && !value.isNull()) ? value.asLong() : null;
    }
}
