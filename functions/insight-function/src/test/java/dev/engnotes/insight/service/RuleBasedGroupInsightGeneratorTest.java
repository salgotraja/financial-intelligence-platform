package dev.engnotes.insight.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.insight.model.CorrelationEdge;
import dev.engnotes.insight.model.GroupInsightContext;
import dev.engnotes.insight.model.MemberSnapshot;
import dev.engnotes.insight.model.Signal;
import dev.engnotes.insight.model.StructuredInsight;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleBasedGroupInsightGeneratorTest {

    private static final double THRESHOLD = 1.0;
    private static final double CONFIDENCE = 0.4;

    private final RuleBasedGroupInsightGenerator generator = new RuleBasedGroupInsightGenerator(THRESHOLD, CONFIDENCE);

    @Test
    void majorityBullishMembersProduceBullishSignal() {
        GroupInsightContext context = context(
                member("RELIANCE.NS", "3.2", 1000L, 500.0),
                member("TCS.NS", "2.5", 900L, 400.0),
                member("INFY.NS", "-0.5", 800L, 300.0));

        StructuredInsight insight = generator.generate(context);

        assertThat(insight.signal()).isEqualTo(Signal.BULLISH);
        assertThat(insight.confidence()).isEqualTo(CONFIDENCE);
        assertThat(insight.rationale()).isNotBlank();
        assertThat(insight.rationale()).contains("g1", "RELIANCE.NS");
    }

    @Test
    void majorityBearishMembersProduceBearishSignal() {
        GroupInsightContext context =
                context(member("RELIANCE.NS", "-3.0", 1000L, 500.0), member("TCS.NS", "-2.2", 900L, 400.0));

        StructuredInsight insight = generator.generate(context);

        assertThat(insight.signal()).isEqualTo(Signal.BEARISH);
    }

    @Test
    void tiedOrSmallMovesAreNeutral() {
        GroupInsightContext context =
                context(member("RELIANCE.NS", "3.0", 1000L, 500.0), member("TCS.NS", "-3.0", 900L, 400.0));

        StructuredInsight insight = generator.generate(context);

        assertThat(insight.signal()).isEqualTo(Signal.NEUTRAL);
    }

    @Test
    void driversIncludeAnomalyPerMemberVolumeAndPairwiseCorrelations() {
        GroupInsightContext context =
                context(member("RELIANCE.NS", "3.2", 1000L, 500.0), member("TCS.NS", "2.5", 900L, 400.0));

        StructuredInsight insight = generator.generate(context);

        assertThat(insight.drivers()).anyMatch(d -> d.contains("anomaly on RELIANCE.NS"));
        assertThat(insight.drivers()).anyMatch(d -> d.contains("RELIANCE.NS") && d.contains("volume"));
        assertThat(insight.drivers()).anyMatch(d -> d.contains("RELIANCE.NS-TCS.NS correlation rho=0.82"));
    }

    @Test
    void missingChangePercentIsSkippedNotTreatedAsZero() {
        GroupInsightContext context = new GroupInsightContext(
                "g1",
                List.of("RELIANCE.NS"),
                "RELIANCE.NS",
                null,
                List.of(new MemberSnapshot("RELIANCE.NS", null, null, null, null)),
                List.of(),
                "window");

        StructuredInsight insight = generator.generate(context);

        assertThat(insight.signal()).isEqualTo(Signal.NEUTRAL);
        assertThat(insight.rationale()).isNotBlank();
    }

    @Test
    void sameInputProducesSameInsight() {
        GroupInsightContext context =
                context(member("RELIANCE.NS", "3.2", 1000L, 500.0), member("TCS.NS", "2.5", 900L, 400.0));

        assertThat(generator.generate(context)).isEqualTo(generator.generate(context));
    }

    private static MemberSnapshot member(String ticker, String changePercent, Long volume, Double baselineMean) {
        return new MemberSnapshot(ticker, new BigDecimal("100"), new BigDecimal(changePercent), volume, baselineMean);
    }

    private static GroupInsightContext context(MemberSnapshot... members) {
        return new GroupInsightContext(
                "g1",
                List.of("RELIANCE.NS", "TCS.NS"),
                "RELIANCE.NS",
                "return z=5.20",
                List.of(members),
                List.of(new CorrelationEdge("RELIANCE.NS", "TCS.NS", 0.82)),
                "30-point window");
    }
}
