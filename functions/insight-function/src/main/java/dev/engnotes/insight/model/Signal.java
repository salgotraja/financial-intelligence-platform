package dev.engnotes.insight.model;

import java.util.Locale;

/**
 * Direction of a generated insight. A closed set so the output contract (spec section 9) is
 * exhaustive and downstream consumers can switch on it without a default branch.
 */
public enum Signal {
    BULLISH,
    BEARISH,
    NEUTRAL;

    /** Parses a model- or rule-supplied signal, case-insensitively. Throws on an unknown value. */
    public static Signal parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("signal is missing");
        }
        return Signal.valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
}
