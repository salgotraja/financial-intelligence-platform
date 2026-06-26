package dev.engnotes.insight.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.insight.model.InsightRequest;
import dev.engnotes.insight.model.Signal;
import dev.engnotes.insight.model.StructuredInsight;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RuleBasedInsightGeneratorTest {

    private static final double THRESHOLD = 1.0;
    private static final double CONFIDENCE = 0.4;

    private final RuleBasedInsightGenerator generator = new RuleBasedInsightGenerator(THRESHOLD, CONFIDENCE);

    @Test
    void positiveMoveBeyondThresholdIsBullish() {
        StructuredInsight insight = generator.generate(request("RELIANCE.NS", "2.5", 1000L));

        assertThat(insight.signal()).isEqualTo(Signal.BULLISH);
        assertThat(insight.confidence()).isEqualTo(CONFIDENCE);
        assertThat(insight.rationale()).isNotBlank();
        assertThat(insight.drivers()).anyMatch(d -> d.contains("change percent"));
    }

    @Test
    void negativeMoveBeyondThresholdIsBearish() {
        StructuredInsight insight = generator.generate(request("TCS.NS", "-3.0", 1000L));

        assertThat(insight.signal()).isEqualTo(Signal.BEARISH);
    }

    @Test
    void smallMoveIsNeutral() {
        StructuredInsight insight = generator.generate(request("INFY.NS", "0.2", 1000L));

        assertThat(insight.signal()).isEqualTo(Signal.NEUTRAL);
    }

    @Test
    void missingChangePercentIsNeutral() {
        InsightRequest data = new InsightRequest();
        data.setTicker("WIPRO.NS");

        StructuredInsight insight = generator.generate(data);

        assertThat(insight.signal()).isEqualTo(Signal.NEUTRAL);
        assertThat(insight.rationale()).isNotBlank();
    }

    @Test
    void fiftyTwoWeekHighBreakoutIsBullishRegardlessOfSmallChange() {
        InsightRequest data = request("HDFCBANK.NS", "0.1", 1000L);
        data.setPrice(new BigDecimal("260"));
        data.setHigh52Week(new BigDecimal("250"));
        data.setLow52Week(new BigDecimal("100"));

        StructuredInsight insight = generator.generate(data);

        assertThat(insight.signal()).isEqualTo(Signal.BULLISH);
        assertThat(insight.drivers()).anyMatch(d -> d.contains("52-week high"));
    }

    @Test
    void anomalyReasonIsCarriedIntoDrivers() {
        InsightRequest data = request("RELIANCE.NS", "5.0", 2000L);
        data.setAnomaly(true);
        data.setAnomalyReason("return z=5.20; volume z=4.10");

        StructuredInsight insight = generator.generate(data);

        assertThat(insight.drivers()).anyMatch(d -> d.contains("return z=5.20"));
    }

    @Test
    void sameInputProducesSameInsight() {
        InsightRequest data = request("RELIANCE.NS", "2.5", 1000L);

        assertThat(generator.generate(data)).isEqualTo(generator.generate(data));
    }

    private static InsightRequest request(String ticker, String changePercent, Long volume) {
        InsightRequest data = new InsightRequest();
        data.setTicker(ticker);
        data.setChangePercent(new BigDecimal(changePercent));
        data.setVolume(volume);
        return data;
    }
}
