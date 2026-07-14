package dev.engnotes.query.model;

/**
 * Read-path output for {@code GET /stories/{ticker}}: a deterministic rule-based narrative
 * assembled from recent daily rollups, the ticker's latest insight, and the latest price point
 * (spec sub-project C, Task 16). {@code source} is always {@code "RULE_BASED"} today; a future
 * Bedrock-backed composer would report {@code "BEDROCK"} behind the same {@link
 * dev.engnotes.query.service.StoryComposer} seam. Unlike the sibling routes there is no {@code
 * found} flag: a story is always returned, degrading to a fixed fallback sentence when nothing
 * composes rather than an empty result.
 */
public record StoryResponse(String ticker, String story, String generatedAt, String source, StoryInputs inputs) {}
