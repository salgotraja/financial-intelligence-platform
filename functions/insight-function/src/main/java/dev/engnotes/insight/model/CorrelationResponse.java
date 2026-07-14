package dev.engnotes.insight.model;

/**
 * Output of a computeCorrelations pass. {@code status} is {@code "computed"} for a normal run or
 * {@code "market-closed"} when the market-hours guard no-opped the pass (weekend, NSE holiday, or
 * outside the 09:00-15:35 IST session).
 */
public record CorrelationResponse(String status, int tickersEvaluated, int groupsComputed, String computedAt) {

    public static CorrelationResponse marketClosed() {
        return new CorrelationResponse("market-closed", 0, 0, null);
    }
}
