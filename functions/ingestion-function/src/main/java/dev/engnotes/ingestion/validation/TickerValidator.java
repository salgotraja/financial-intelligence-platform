package dev.engnotes.ingestion.validation;

import dev.engnotes.ingestion.exception.MarketDataException;
import java.util.regex.Pattern;

/**
 * Strict allowlist validation for a ticker at the ingestion trust boundary (spec section 12).
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

    /** Returns the ticker unchanged if it matches the allowlist; throws otherwise. */
    public static String validate(String ticker) {
        if (ticker == null || !ALLOWED.matcher(ticker).matches()) {
            throw new MarketDataException("Ticker failed allowlist validation (expected " + ALLOWED.pattern() + ")");
        }
        return ticker;
    }
}
