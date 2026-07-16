package dev.engnotes.watchlist.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.engnotes.watchlist.exception.WatchlistException;
import org.junit.jupiter.api.Test;

class TickerValidatorTest {

    @Test
    void acceptsNseSymbol() {
        assertThat(TickerValidator.validate("RELIANCE.NS")).isEqualTo("RELIANCE.NS");
    }

    @Test
    void acceptsIndexWithCaret() {
        assertThat(TickerValidator.validate("^NSEI")).isEqualTo("^NSEI");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> TickerValidator.validate(null)).isInstanceOf(WatchlistException.class);
    }

    @Test
    void rejectsLowercase() {
        assertThatThrownBy(() -> TickerValidator.validate("reliance")).isInstanceOf(WatchlistException.class);
    }

    @Test
    void rejectsInjectionChars() {
        assertThatThrownBy(() -> TickerValidator.validate("A/../B")).isInstanceOf(WatchlistException.class);
    }

    @Test
    void rejectsTooLong() {
        assertThatThrownBy(() -> TickerValidator.validate("ABCDEFGHIJKLMNOP")).isInstanceOf(WatchlistException.class);
    }

    @Test
    void decodesEncodedCaretIndexTickerBeforeValidating() {
        // Browsers percent-encode ^ in URL path segments: POST /watchlist/%5ENSEI must resolve to ^NSEI.
        assertThat(TickerValidator.validate("%5ENSEI")).isEqualTo("^NSEI");
    }

    @Test
    void passesThroughAlreadyPlainTickerUnchanged() {
        assertThat(TickerValidator.validate("RELIANCE.NS")).isEqualTo("RELIANCE.NS");
    }

    @Test
    void rejectsMalformedPercentEncoding() {
        // %2G is not a valid hex escape: URLDecoder throws, decode() returns null, allowlist rejects.
        assertThatThrownBy(() -> TickerValidator.validate("RELIANCE%2GNS")).isInstanceOf(WatchlistException.class);
    }

    @Test
    void rejectionMessageMatchesQueryStackClientErrorPattern() {
        // QueryStack's CLIENT_ERROR_PATTERN maps this to HTTP 400 by matching "allowlist validation".
        assertThatThrownBy(() -> TickerValidator.validate("RELIANCE%2FNS"))
                .isInstanceOf(WatchlistException.class)
                .hasMessageContaining("allowlist validation");
    }
}
