package dev.engnotes.insight.service;

import dev.engnotes.insight.model.CorrelationEdge;
import dev.engnotes.insight.model.GroupInsightContext;
import dev.engnotes.insight.model.MemberSnapshot;
import dev.engnotes.insight.model.Signal;
import dev.engnotes.insight.model.StructuredInsight;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deterministic, cross-ticker variant of {@link RuleBasedInsightGenerator} (spec section 9): the
 * fallback when Bedrock is unavailable for a group insight. The signal is a majority vote of each
 * member's percent change against the same static threshold the per-ticker generator uses; drivers
 * are built from the triggering anomaly, every member's change/volume-vs-baseline, and the group's
 * pairwise correlations, so the fallback reasons over the same data the LLM prompt would have seen.
 * Deterministic because {@code CorrelationGroup.members()} is sorted, so the same group input
 * always produces the same driver ordering and insight.
 */
@Component
public class RuleBasedGroupInsightGenerator {

    private final double bullishThresholdPercent;
    private final double fallbackConfidence;

    public RuleBasedGroupInsightGenerator(
            @Value("${RULE_BULLISH_THRESHOLD_PERCENT:1.0}") double bullishThresholdPercent,
            @Value("${RULE_FALLBACK_CONFIDENCE:0.4}") double fallbackConfidence) {
        this.bullishThresholdPercent = bullishThresholdPercent;
        this.fallbackConfidence = fallbackConfidence;
    }

    public StructuredInsight generate(GroupInsightContext context) {
        Signal signal = deriveSignal(context);
        List<String> drivers = buildDrivers(context);
        String rationale = buildRationale(context, signal);
        return new StructuredInsight(signal, fallbackConfidence, rationale, drivers);
    }

    private Signal deriveSignal(GroupInsightContext context) {
        int bullish = 0;
        int bearish = 0;
        for (MemberSnapshot member : context.members()) {
            if (member.changePercent() == null) {
                continue;
            }
            double pct = member.changePercent().doubleValue();
            if (pct >= bullishThresholdPercent) {
                bullish++;
            } else if (pct <= -bullishThresholdPercent) {
                bearish++;
            }
        }
        if (bullish > bearish) {
            return Signal.BULLISH;
        }
        if (bearish > bullish) {
            return Signal.BEARISH;
        }
        return Signal.NEUTRAL;
    }

    private List<String> buildDrivers(GroupInsightContext context) {
        List<String> drivers = new ArrayList<>();
        if (context.anomalyReason() != null && !context.anomalyReason().isBlank()) {
            drivers.add("anomaly on " + context.triggeringTicker() + ": "
                    + context.anomalyReason().strip());
        }
        for (MemberSnapshot member : context.members()) {
            drivers.add(memberDriver(member));
        }
        for (CorrelationEdge edge : context.pairwiseRhos()) {
            drivers.add("%s-%s correlation rho=%.2f".formatted(edge.tickerA(), edge.tickerB(), edge.rho()));
        }
        return drivers;
    }

    private String memberDriver(MemberSnapshot member) {
        StringBuilder driver = new StringBuilder(member.ticker()).append(": ");
        driver.append(
                member.changePercent() == null
                        ? "change unavailable"
                        : member.changePercent().toPlainString() + "% change");
        if (member.volume() != null) {
            driver.append(", volume ").append(member.volume());
            if (member.baselineVolumeMean() != null && member.baselineVolumeMean() > 0) {
                driver.append(" (%.2fx baseline)".formatted(member.volume() / member.baselineVolumeMean()));
            }
        }
        return driver.toString();
    }

    private String buildRationale(GroupInsightContext context, Signal signal) {
        return "Rule-based cross-ticker fallback insight for group " + context.groupId() + " ("
                + String.join(", ", context.tickers()) + "): " + signal + " across correlated tickers, triggered by "
                + context.triggeringTicker()
                + ". Generated from static thresholds because the model was unavailable.";
    }
}
