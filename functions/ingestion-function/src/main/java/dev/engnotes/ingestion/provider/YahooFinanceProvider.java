package dev.engnotes.ingestion.provider;

import dev.engnotes.ingestion.exception.MarketDataException;
import dev.engnotes.ingestion.model.MarketDataResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Primary market-data provider: Yahoo Finance v8 chart API (spec section 8).
 *
 * <p>Keyless and broad NSE/BSE coverage, but it rate-limits or blocks AWS datacenter IPs and has no
 * SLA, so it is the primary with {@link AlphaVantageProvider} as failover. All field access is
 * null-safe: a missing field yields a partial response rather than failing the pipeline.
 */
@Component
@Order(1)
public class YahooFinanceProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceProvider.class);

    private static final String NAME = "yahoo-finance";
    private static final String URL = "https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=1m&range=1d";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final java.net.http.HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public YahooFinanceProvider(java.net.http.HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public MarketDataResponse fetch(String ticker, String correlationId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(URL, URLEncoder.encode(ticker, StandardCharsets.UTF_8))))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "financial-intelligence-platform/1.0")
                .header("X-Correlation-Id", correlationId)
                .GET()
                .build();

        String body = ProviderHttp.sendForJson(httpClient, request, NAME, ticker);
        MarketDataResponse data = parse(body, ticker, correlationId);
        log.info("Yahoo Finance fetch complete. ticker={} correlationId={}", ticker, correlationId);
        return data;
    }

    private MarketDataResponse parse(String json, String ticker, String correlationId) {
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
                            ? change.divide(previousClose, 4, RoundingMode.HALF_UP)
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
                    .dataSource(NAME)
                    .stored(false)
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
