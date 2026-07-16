package dev.engnotes.ingestion.validation;

import dev.engnotes.ingestion.exception.MarketDataException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Strict allowlist validation for a ticker at the ingestion trust boundary (spec section 12).
 *
 * <p>On-demand requests ({@code POST /ingest/{ticker}}) carry a raw, percent-encoded path segment
 * (e.g. index symbols like {@code %5ENSEI} for {@code ^NSEI}), mirroring query-function's {@code
 * Tickers.validated}. Decode first, then apply the strict allowlist, so encoded indices resolve to
 * the same {@code TICKER#^NSEI} key the rest of the pipeline uses. Scheduled requests carry plain,
 * unencoded tickers, for which decoding is a no-op.
 *
 * <p>The ticker is interpolated into the provider URL and later into S3 keys, S3 tags, and DynamoDB
 * writes. Validating it against a tight allowlist before it reaches any of those sinks closes the
 * URL-injection, S3 key/tag-injection, and log-forging vectors in one place. The rejection message
 * deliberately omits the raw value, since echoing attacker-controlled input (e.g. embedded newlines)
 * into a log line is itself the log-forging vector this guard exists to close.
 */
public final class TickerValidator {

    // NSE/BSE symbols and indices: uppercase letters, digits, dot, caret, hyphen; 1-15 chars.
    private static final Pattern ALLOWED = Pattern.compile("^[A-Z0-9.^-]{1,15}$");

    private TickerValidator() {}

    /** Decodes the ticker, then returns it if it matches the allowlist; throws otherwise. */
    public static String validate(String ticker) {
        String decoded = decode(ticker);
        if (decoded == null || !ALLOWED.matcher(decoded).matches()) {
            throw new MarketDataException("Ticker failed allowlist validation (expected " + ALLOWED.pattern() + ")");
        }
        return decoded;
    }

    private static String decode(String ticker) {
        if (ticker == null) {
            return null;
        }
        try {
            return URLDecoder.decode(ticker, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null; // malformed percent-encoding -> rejected by the allowlist check
        }
    }
}
