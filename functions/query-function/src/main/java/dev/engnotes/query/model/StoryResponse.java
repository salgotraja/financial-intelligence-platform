package dev.engnotes.query.model;

/**
 * Read-path output for {@code GET /stories/{ticker}}: a deterministic rule-based narrative
 * assembled from recent daily rollups, the ticker's latest insight, and the latest price point
 * (spec sub-project C, Task 16). {@code source} is always {@code "RULE_BASED"} today; a future
 * Bedrock-backed composer would report {@code "BEDROCK"} behind the same {@link
 * dev.engnotes.query.service.StoryComposer} seam. {@code found} follows the sibling routes'
 * semantics (a normal empty result, not an error, never a 404): true whenever at least one
 * sentence composed from real data, false only on the nothing-composes fallback - a story string
 * is still returned either way, so consumers branch on the boolean, never on the sentence text.
 */
public record StoryResponse(
        String ticker, String story, String generatedAt, String source, StoryInputs inputs, boolean found) {}
