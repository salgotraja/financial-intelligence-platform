package dev.engnotes.platform.stacks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Insertion-ordered replacement for {@code Map.of(...)} calls with 2+ entries.
 * <p>
 * {@code Map.of}/{@code Map.ofEntries} iterate in a per-JVM-run salted order
 * (java.util.ImmutableCollections.SALT32L), so two {@code cdk synth} invocations of identical
 * source can marshal the same map's entries to jsii in a different order, producing order-only
 * CloudFormation template diffs (Step Functions Parameters, dashboard widget metric lists, API
 * Gateway request parameters, and similar). Use this wherever synthesized template content
 * depends on a fixed key order.
 */
final class OrderedMap {
    private OrderedMap() {}

    @SafeVarargs
    static <K, V> Map<K, V> of(Map.Entry<K, V>... entries) {
        var map = new LinkedHashMap<K, V>();
        for (var entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }
}
