package dev.engnotes.platform.stacks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// Pins OrderedMap's Map.of-equivalent contract (fail fast on a duplicate key) that a plain
// LinkedHashMap.put loop silently drops: Map.of throws IllegalArgumentException on a duplicate
// key, so OrderedMap.of must too, rather than letting the later entry silently win (found in
// review of 8bcae98, the synth-determinism fix).
class OrderedMapTest {

    @Test
    void preservesInsertionOrderOfDistinctKeys() {
        Map<String, String> map = OrderedMap.of(Map.entry("b", "2"), Map.entry("a", "1"), Map.entry("c", "3"));

        assertEquals(List.of("b", "a", "c"), List.copyOf(map.keySet()));
        assertEquals(Map.of("a", "1", "b", "2", "c", "3"), map);
    }

    @Test
    void throwsOnDuplicateKeyNamingIt() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> OrderedMap.of(Map.entry("PLATFORM_TABLE", "one"), Map.entry("PLATFORM_TABLE", "two")));

        assertTrue(
                exception.getMessage().contains("PLATFORM_TABLE"),
                "expected the duplicate key in the exception message: " + exception.getMessage());
    }

    @Test
    void mergeOverloadAppendsToBaseInOrder() {
        Map<String, String> base = OrderedMap.of(Map.entry("PLATFORM_TABLE", "table"), Map.entry("ENVIRONMENT", "dev"));

        Map<String, String> merged = OrderedMap.of(base, Map.entry("MAIN_CLASS", "Handler"));

        assertEquals(List.of("PLATFORM_TABLE", "ENVIRONMENT", "MAIN_CLASS"), List.copyOf(merged.keySet()));
        assertEquals(Map.of("PLATFORM_TABLE", "table", "ENVIRONMENT", "dev", "MAIN_CLASS", "Handler"), merged);
    }

    @Test
    void mergeOverloadThrowsWhenAdditionalEntryDuplicatesABaseKey() {
        Map<String, String> base = OrderedMap.of(Map.entry("PLATFORM_TABLE", "table"));

        var exception = assertThrows(
                IllegalArgumentException.class, () -> OrderedMap.of(base, Map.entry("PLATFORM_TABLE", "overwritten")));

        assertTrue(
                exception.getMessage().contains("PLATFORM_TABLE"),
                "expected the duplicate key in the exception message: " + exception.getMessage());
    }
}
