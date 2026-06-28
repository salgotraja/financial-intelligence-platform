package dev.engnotes.watchlist.validation;

import dev.engnotes.watchlist.exception.WatchlistException;
import java.util.regex.Pattern;

/**
 * Strict allowlist validation for a ticker at the watchlist trust boundary (spec section 12).
 *
 * <p>The ticker is interpolated into DynamoDB keys (the user's watchlist item and the WATCHSET
 * union entry). Validating it against a tight allowlist before it reaches those sinks closes the
 * key-injection and log-forging vectors in one place. The rejection message omits the raw value,
 * since echoing attacker-controlled input into a log line is itself the log-forging vector this
 * guard closes. Duplicated per module by design: the function modules share no code.
 */
public final class TickerValidator {

    // NSE/BSE symbols and indices: uppercase letters, digits, dot, caret, hyphen; 1-15 chars.
    private static final Pattern ALLOWED = Pattern.compile("^[A-Z0-9.^-]{1,15}$");

    private TickerValidator() {}

    /** Returns the ticker unchanged if it matches the allowlist; throws otherwise. */
    public static String validate(String ticker) {
        if (ticker == null || !ALLOWED.matcher(ticker).matches()) {
            throw new WatchlistException("Ticker failed allowlist validation (expected " + ALLOWED.pattern() + ")");
        }
        return ticker;
    }
}
