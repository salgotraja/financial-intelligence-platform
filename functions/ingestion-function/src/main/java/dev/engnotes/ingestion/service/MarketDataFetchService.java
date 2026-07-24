package dev.engnotes.ingestion.service;

import dev.engnotes.ingestion.exception.MarketDataException;
import dev.engnotes.ingestion.model.MarketDataResponse;
import dev.engnotes.ingestion.provider.MarketDataProvider;
import dev.engnotes.observability.Metrics;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Failover orchestrator over the ordered {@link MarketDataProvider} beans (spec section 8).
 *
 * <p>Tries each provider in {@code @Order} (Yahoo primary, Alpha Vantage fallback) and returns the
 * first quote; a provider failure or throttle falls through to the next. Only when every provider
 * fails does the fetch throw, which the Step Functions catch routes to the DLQ. Coarse retry with
 * exponential backoff and full jitter is owned by the Step Functions {@code standardRetry} at the
 * state boundary, so this layer adds source failover rather than duplicating in-Lambda retry.
 */
@Service
public class MarketDataFetchService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataFetchService.class);

    private final List<MarketDataProvider> providers;
    private final Metrics metrics;

    public MarketDataFetchService(List<MarketDataProvider> providers, Metrics metrics) {
        if (providers.isEmpty()) {
            throw new IllegalStateException("No market data providers configured");
        }
        this.providers = providers;
        this.metrics = metrics;
    }

    public MarketDataResponse fetch(String ticker, String correlationId) {
        long startMs = System.currentTimeMillis();
        MarketDataException lastFailure = null;

        for (MarketDataProvider provider : providers) {
            try {
                MarketDataResponse data = provider.fetch(ticker, correlationId);
                log.info(
                        "Market data fetch complete. ticker={} provider={} latencyMs={} correlationId={}",
                        ticker,
                        provider.name(),
                        System.currentTimeMillis() - startMs,
                        correlationId);
                return data;
            } catch (Exception e) {
                lastFailure = e instanceof MarketDataException mde
                        ? mde
                        : new MarketDataException(provider.name() + " failed for ticker " + ticker, e);
                log.warn(
                        "Provider failed, attempting failover. ticker={} provider={} error={} correlationId={}",
                        ticker,
                        provider.name(),
                        e.toString(),
                        correlationId);
            }
        }

        metrics.count("IngestionFailure", "reason", "provider_exhausted");
        throw new MarketDataException("All market data providers failed for ticker " + ticker, lastFailure);
    }
}
