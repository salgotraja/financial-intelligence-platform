package dev.engnotes.insight.model;

import java.util.List;
import java.util.Objects;

/**
 * The strict insight contract (spec section 9): the judgment the model (or the rule-based
 * fallback) produces, independent of identity and time, which the service stamps on afterwards.
 *
 * <p>The compact constructor is the schema validator: an out-of-range confidence, a blank
 * rationale, or a missing signal fails construction, so an invalid Bedrock response is rejected at
 * the boundary and triggers a retry or the fallback rather than persisting garbage.
 */
public record StructuredInsight(Signal signal, double confidence, String rationale, List<String> drivers) {

    public StructuredInsight {
        Objects.requireNonNull(signal, "signal is missing");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence out of [0,1]: " + confidence);
        }
        if (rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException("rationale is blank");
        }
        drivers = drivers == null ? List.of() : List.copyOf(drivers);
    }
}
