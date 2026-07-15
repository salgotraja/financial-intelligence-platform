package dev.engnotes.query.service;

import dev.engnotes.query.model.DailyPoint;
import dev.engnotes.query.model.FeedInsight;
import dev.engnotes.query.model.MarketDataPoint;
import java.util.List;
import java.util.Optional;

/**
 * Pure narrative composer: turns assembled read-path data into a deterministic story string, with
 * no IO of its own (StoryQuery owns every DynamoDB read). One method only, so a future
 * BedrockStoryComposer can implement the same seam without any factory/strategy machinery - two
 * implementations of one method is the ceiling of abstraction here (spec sub-project C, Task 16).
 */
public interface StoryComposer {

    /**
     * The composed narrative plus whether any sentence derived from real data. {@code found} is
     * false only when the composer fell back to its fixed no-history sentence ({@code story} is
     * still never null or blank), mirroring the sibling routes' found=false-on-empty semantics.
     */
    record Composition(String story, boolean found) {}

    /**
     * @param ticker the validated ticker the story is about
     * @param days up to 7 daily rollups, newest first (may be empty)
     * @param insight the ticker's latest insight, group-aware, if one exists
     * @param latestPoint the ticker's latest stored price point, if one exists
     * @return a deterministic multi-sentence story and its found flag
     */
    Composition compose(
            String ticker, List<DailyPoint> days, Optional<FeedInsight> insight, Optional<MarketDataPoint> latestPoint);
}
