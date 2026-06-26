package dev.engnotes.insight.service;

import dev.engnotes.insight.model.InsightRequest;
import dev.engnotes.insight.model.Signal;
import dev.engnotes.insight.model.StructuredInsight;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deterministic, rule-based fallback insight (spec section 9).
 *
 * <p>Used when Bedrock is throttled, times out, returns repeatedly invalid output, or the cost
 * circuit breaker is open. It produces the same {@link StructuredInsight} contract from the same
 * anomaly and market data the LLM would have seen, so the platform always returns a usable insight
 * instead of failing the user. The signal comes from the static thresholds (change percent and the
 * 52-week break, section 6); confidence is fixed and deliberately lower than an LLM insight; drivers
 * are the triggering statistics. No randomness, so the same input always yields the same insight.
 */
@Component
public class RuleBasedInsightGenerator {

    private final double bullishThresholdPercent;
    private final double fallbackConfidence;

    public RuleBasedInsightGenerator(
            @Value("${RULE_BULLISH_THRESHOLD_PERCENT:1.0}") double bullishThresholdPercent,
            @Value("${RULE_FALLBACK_CONFIDENCE:0.4}") double fallbackConfidence) {
        this.bullishThresholdPercent = bullishThresholdPercent;
        this.fallbackConfidence = fallbackConfidence;
    }

    public StructuredInsight generate(InsightRequest data) {
        boolean breakoutHigh = isBreakoutHigh(data);
        boolean breakdownLow = isBreakdownLow(data);
        Signal signal = deriveSignal(data, breakoutHigh, breakdownLow);
        List<String> drivers = buildDrivers(data, breakoutHigh, breakdownLow);
        String rationale = buildRationale(data, signal);
        return new StructuredInsight(signal, fallbackConfidence, rationale, drivers);
    }

    private Signal deriveSignal(InsightRequest data, boolean breakoutHigh, boolean breakdownLow) {
        if (breakoutHigh) {
            return Signal.BULLISH;
        }
        if (breakdownLow) {
            return Signal.BEARISH;
        }
        BigDecimal changePercent = data.getChangePercent();
        if (changePercent == null) {
            return Signal.NEUTRAL;
        }
        double pct = changePercent.doubleValue();
        if (pct >= bullishThresholdPercent) {
            return Signal.BULLISH;
        }
        if (pct <= -bullishThresholdPercent) {
            return Signal.BEARISH;
        }
        return Signal.NEUTRAL;
    }

    private List<String> buildDrivers(InsightRequest data, boolean breakoutHigh, boolean breakdownLow) {
        List<String> drivers = new ArrayList<>();
        if (data.getAnomalyReason() != null && !data.getAnomalyReason().isBlank()) {
            drivers.add("anomaly: " + data.getAnomalyReason().strip());
        }
        if (data.getChangePercent() != null) {
            drivers.add("change percent: " + data.getChangePercent().toPlainString());
        }
        if (data.getVolume() != null) {
            drivers.add("volume: " + data.getVolume());
        }
        if (breakoutHigh) {
            drivers.add("price broke the 52-week high");
        }
        if (breakdownLow) {
            drivers.add("price broke the 52-week low");
        }
        return drivers;
    }

    private String buildRationale(InsightRequest data, Signal signal) {
        String change = data.getChangePercent() == null
                ? "an unavailable change"
                : data.getChangePercent().toPlainString() + "%";
        return "Rule-based fallback insight for " + data.getTicker() + ": " + signal + " on a move of " + change
                + ". Generated from static thresholds because the model was unavailable.";
    }

    private boolean isBreakoutHigh(InsightRequest data) {
        return data.getPrice() != null
                && data.getHigh52Week() != null
                && data.getPrice().compareTo(data.getHigh52Week()) > 0;
    }

    private boolean isBreakdownLow(InsightRequest data) {
        return data.getPrice() != null
                && data.getLow52Week() != null
                && data.getPrice().compareTo(data.getLow52Week()) < 0;
    }
}
