package dev.engnotes.ingestion.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.engnotes.ingestion.exception.MarketDataException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TickerValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"RELIANCE.NS", "TCS.BO", "INFY.NS", "^NSEI", "^BSESN", "A", "ABCDEFGHIJ12345"})
    void acceptsWellFormedTickersAndReturnsThem(String ticker) {
        assertThat(TickerValidator.validate(ticker)).isEqualTo(ticker);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "reliance.ns", // lowercase
                "ABCDEFGHIJ123456", // 16 chars, over the limit
                "RELIANCE NS", // space
                "../../etc/passwd", // path traversal
                "RELIANCE.NS?x=1", // query injection
                "RELIANCE.NS#frag", // fragment injection
                "RELIANCE@evil.com", // host injection
                "RELIANCE/NS", // slash
                "RELIANCE%2eNS", // percent-encoding
                "<script>", // markup
                "RELIANCE\n.NS" // newline / log-forging
            })
    void rejectsMalformedOrInjectionTickers(String ticker) {
        assertThatThrownBy(() -> TickerValidator.validate(ticker)).isInstanceOf(MarketDataException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> TickerValidator.validate(null)).isInstanceOf(MarketDataException.class);
    }

    @Test
    void rejectsEmpty() {
        assertThatThrownBy(() -> TickerValidator.validate("")).isInstanceOf(MarketDataException.class);
    }

    @Test
    void rejectionMessageDoesNotEchoTheRawTicker() {
        // The raw value is the log-forging vector, so it must never appear in the message.
        String malicious = "EVIL\nINJECTED-LOG-LINE";
        assertThatThrownBy(() -> TickerValidator.validate(malicious))
                .isInstanceOf(MarketDataException.class)
                .hasMessageNotContaining("INJECTED-LOG-LINE");
    }
}
