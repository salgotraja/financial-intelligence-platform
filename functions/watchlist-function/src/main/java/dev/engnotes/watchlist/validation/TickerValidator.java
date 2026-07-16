package dev.engnotes.watchlist.validation;

import dev.engnotes.watchlist.exception.WatchlistException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Strict allowlist validation for a ticker at the watchlist trust boundary (spec section 12).
 *
 * <p>API Gateway forwards the raw path segment via {@code $input.params('ticker')}, so an index
 * symbol arrives percent-encoded (e.g. {@code %5ENSEI}). Decode first, then apply the strict
 * allowlist, mirroring query-function's {@code Tickers.validated}, so {@code ^}-prefixed indices
 * resolve to the same {@code TICKER#^NSEI} key ingestion writes.
 *
 * <p>The ticker is interpolated into DynamoDB keys (the user's watchlist item and the WATCHSET
 * union entry). Validating it against a tight allowlist before it reaches those sinks closes the
 * key-injection and log-forging vectors in one place. The rejection message omits the raw value,
 * since echoing attacker-controlled input into a log line is itself the log-forging vector this
 * guard closes. Duplicated per module by design: the function modules share no code.
 *
 * <p>The message keeps the "allowlist validation" substring that QueryStack's CLIENT_ERROR_PATTERN
 * matches to map this exception to HTTP 400 instead of 500; changing it here requires the matching
 * change in QueryStack.
 */
public final class TickerValidator {

    // NSE/BSE symbols and indices: uppercase letters, digits, dot, caret, hyphen; 1-15 chars.
    private static final Pattern ALLOWED = Pattern.compile("^[A-Z0-9.^-]{1,15}$");

    private TickerValidator() {}

    /** Decodes the ticker, then returns it if it matches the allowlist; throws otherwise. */
    public static String validate(String ticker) {
        String decoded = decode(ticker);
        if (decoded == null || !ALLOWED.matcher(decoded).matches()) {
            throw new WatchlistException("Ticker failed allowlist validation (expected " + ALLOWED.pattern() + ")");
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
