package dev.engnotes.watchlist.portfolio;

import dev.engnotes.watchlist.model.Lot;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Distinguishes a pure lot addition from a mutation (edit or removal) of an existing lot. Drives the
 * time-machine curve floor: a sell/edit of an existing lot bumps {@code lastLotMutation}, a pure
 * new-lot addition does not.
 */
public final class LotMutationDetector {

    private LotMutationDetector() {}

    /**
     * Returns {@code true} if {@code newLots} changes or removes any lot present in {@code oldLots}.
     * Lots are compared as a multiset by {@code (buyDate, qty, price)} equality (record equals). A
     * pure addition, where every old lot is still present with at least its original multiplicity,
     * returns {@code false}.
     */
    public static boolean isExistingLotMutation(List<Lot> oldLots, List<Lot> newLots) {
        if (oldLots.isEmpty()) {
            return false;
        }
        Map<Lot, Long> oldCounts =
                oldLots.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        Map<Lot, Long> newCounts =
                newLots.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        return oldCounts.entrySet().stream()
                .anyMatch(entry -> newCounts.getOrDefault(entry.getKey(), 0L) < entry.getValue());
    }
}
