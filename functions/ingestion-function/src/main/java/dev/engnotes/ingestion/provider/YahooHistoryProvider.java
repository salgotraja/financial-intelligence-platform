package dev.engnotes.ingestion.provider;

import dev.engnotes.ingestion.exception.MarketDataException;
import dev.engnotes.ingestion.model.DailyBar;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Historical daily bars from the Yahoo Finance v8 chart API (range=1y, interval=1d) — the same
 * endpoint the live quote provider calls, with the OHLC arrays parsed instead of ignored. Used
 * only by the watchlist-add backfill, never by the live ingest path. Yahoo-only, no failover:
 * the backfill is idempotent and rerunnable, so a failed call is retried by the stream mapping
 * or the sweep script rather than a second provider.
 */
@Component
public class YahooHistoryProvider {

    private static final Logger log = LoggerFactory.getLogger(YahooHistoryProvider.class);

    private static final String NAME = "yahoo-finance-history";
    private static final String URL = "https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=1d&range=1y";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    // NSE trading dates: the bar's calendar day in exchange-local time, same zone the daily
    // rollups use for their DAY# date.
    private static final ZoneId TRADING_ZONE = ZoneId.of("Asia/Kolkata");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public YahooHistoryProvider(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /** Up to ~250 daily bars, ascending by date; bars without a close are dropped. */
    public List<DailyBar> fetchDailyBars(String ticker, String correlationId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(URL, URLEncoder.encode(ticker, StandardCharsets.UTF_8))))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "financial-intelligence-platform/1.0")
                .header("X-Correlation-Id", correlationId)
                .GET()
                .build();

        String body = ProviderHttp.sendForJson(httpClient, request, NAME, ticker);
        List<DailyBar> bars = parse(body, ticker);
        log.info(
                "Yahoo history fetch complete. ticker={} bars={} correlationId={}", ticker, bars.size(), correlationId);
        return bars;
    }

    private List<DailyBar> parse(String json, String ticker) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode result = root.path("chart").path("result").get(0);
            JsonNode timestamps = result.path("timestamp");
            JsonNode quote = result.path("indicators").path("quote").get(0);

            List<DailyBar> bars = new ArrayList<>();
            for (int i = 0; i < timestamps.size(); i++) {
                BigDecimal close = element(quote, "close", i);
                if (close == null) {
                    continue; // Yahoo emits null slots; a bar without a close is unusable
                }
                LocalDate date = LocalDate.ofInstant(
                        Instant.ofEpochSecond(timestamps.get(i).asLong()), TRADING_ZONE);
                bars.add(new DailyBar(
                        date,
                        element(quote, "open", i),
                        element(quote, "high", i),
                        element(quote, "low", i),
                        close,
                        longElement(quote, "volume", i)));
            }
            return bars;
        } catch (MarketDataException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketDataException("Failed to parse Yahoo history response for ticker " + ticker, e);
        }
    }

    private static BigDecimal element(JsonNode quote, String field, int index) {
        JsonNode value = quote.path(field).get(index);
        return value == null || value.isNull() ? null : new BigDecimal(value.asText());
    }

    private static Long longElement(JsonNode quote, String field, int index) {
        JsonNode value = quote.path(field).get(index);
        return value == null || value.isNull() ? null : value.asLong();
    }
}
