package dev.engnotes.ingestion.provider;

import dev.engnotes.ingestion.exception.MarketDataException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Shared HTTP guards for market-data providers (spec section 8): both providers send through here so
 * they harden identically. Validates the status, requires a JSON content type before any parsing,
 * and caps the body size.
 *
 * <p>The size check runs after buffering (these quote endpoints return a few KB), so it is a
 * parse-guard against an unexpectedly large or hostile payload, not a streaming memory cap. The
 * ticker reaching here is already allowlist-validated at the handler trust boundary.
 */
final class ProviderHttp {

    // Quote responses are a few KB; reject anything wildly larger before handing it to the parser.
    private static final int MAX_BODY_CHARS = 1_000_000;

    private ProviderHttp() {}

    static String sendForJson(HttpClient http, HttpRequest request, String provider, String ticker) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new MarketDataException(provider + " request failed for ticker " + ticker, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketDataException(provider + " request interrupted for ticker " + ticker, e);
        }

        if (response.statusCode() != 200) {
            throw new MarketDataException(
                    provider + " returned status " + response.statusCode() + " for ticker " + ticker);
        }

        String contentType = response.headers().firstValue("content-type").orElse("");
        if (!contentType.toLowerCase().contains("json")) {
            throw new MarketDataException(provider + " returned non-JSON content type for ticker " + ticker);
        }

        String body = response.body();
        if (body != null && body.length() > MAX_BODY_CHARS) {
            throw new MarketDataException(provider + " response exceeded the size cap for ticker " + ticker);
        }
        return body;
    }
}
