package dev.engnotes.query.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Normalizes a path-supplied ticker before it reaches a DynamoDB key.
 *
 * <p>API Gateway forwards the raw path segment via {@code $input.params('ticker')}, so an index
 * symbol arrives percent-encoded (e.g. {@code %5ENSEI}). Decode first, then apply the strict
 * allowlist, so {@code ^}-prefixed indices resolve to the same {@code TICKER#^NSEI} key ingestion
 * wrote. The thrown message keeps the "Invalid ticker" prefix that QueryStack's 400 selection
 * pattern matches.
 */
final class Tickers {

    // NSE/BSE symbols and indices: uppercase letters, digits, dot, caret, hyphen; 1-15 chars.
    private static final Pattern ALLOWED = Pattern.compile("^[A-Z0-9.^-]{1,15}$");

    private Tickers() {}

    static String validated(String raw) {
        String ticker = decode(raw);
        if (ticker == null || !ALLOWED.matcher(ticker).matches()) {
            throw new IllegalArgumentException("Invalid ticker: " + raw);
        }
        return ticker;
    }

    private static String decode(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null; // malformed percent-encoding -> rejected by the allowlist check
        }
    }
}
