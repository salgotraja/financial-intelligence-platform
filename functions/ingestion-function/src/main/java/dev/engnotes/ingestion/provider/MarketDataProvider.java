package dev.engnotes.ingestion.provider;

import dev.engnotes.ingestion.model.MarketDataResponse;

/**
 * A single market-data source (spec section 8). Implementations are ordered beans; the
 * {@link dev.engnotes.ingestion.service.MarketDataFetchService} tries them in order and fails over
 * to the next on any failure or throttle.
 *
 * <p>Implementations throw {@link dev.engnotes.ingestion.exception.MarketDataException} on a non-200
 * status, an unexpected content type, an oversize body, or a parse failure, so the orchestrator can
 * distinguish a clean miss (try the next provider) from a returned quote.
 */
public interface MarketDataProvider {

    /** Stable identifier recorded as {@code dataSource} on the response and used in logs. */
    String name();

    /** Fetches a quote for the (already allowlist-validated) ticker, or throws on failure. */
    MarketDataResponse fetch(String ticker, String correlationId);
}
