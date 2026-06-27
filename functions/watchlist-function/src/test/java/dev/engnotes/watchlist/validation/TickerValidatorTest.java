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
}
