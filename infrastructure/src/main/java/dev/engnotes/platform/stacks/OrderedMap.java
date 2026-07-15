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
            putUnique(map, entry);
        }
        return map;
    }

    /**
     * Same as {@link #of(Map.Entry[])}, seeded with {@code base}'s entries first (in {@code base}'s
     * own iteration order) before appending {@code additionalEntries}. Lets a shared base map -
     * itself built by {@link #of(Map.Entry[])} - be reused across several derived maps without
     * re-typing its entries at every call site.
     */
    @SafeVarargs
    static <K, V> Map<K, V> of(Map<K, V> base, Map.Entry<K, V>... additionalEntries) {
        var map = new LinkedHashMap<>(base);
        for (var entry : additionalEntries) {
            putUnique(map, entry);
        }
        return map;
    }

    private static <K, V> void putUnique(Map<K, V> map, Map.Entry<K, V> entry) {
        if (map.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
            throw new IllegalArgumentException("duplicate key: " + entry.getKey());
        }
    }
}
