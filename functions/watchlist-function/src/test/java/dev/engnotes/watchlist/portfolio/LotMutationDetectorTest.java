package dev.engnotes.watchlist.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.watchlist.model.Lot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class LotMutationDetectorTest {

    private static Lot lot(String date, int qty, String price) {
        return new Lot(LocalDate.parse(date), qty, new BigDecimal(price));
    }

    @Test
    void pureAdditionIsNotAMutation() {
        List<Lot> oldLots = List.of(lot("2024-01-10", 10, "100"));
        List<Lot> newLots = List.of(lot("2024-01-10", 10, "100"), lot("2024-02-01", 5, "200"));

        assertThat(LotMutationDetector.isExistingLotMutation(oldLots, newLots)).isFalse();
    }

    @Test
    void removingAnExistingLotIsAMutation() {
        List<Lot> oldLots = List.of(lot("2024-01-10", 10, "100"), lot("2024-02-01", 5, "200"));
        List<Lot> newLots = List.of(lot("2024-01-10", 10, "100"));

        assertThat(LotMutationDetector.isExistingLotMutation(oldLots, newLots)).isTrue();
    }

    @Test
    void changingAnExistingLotQtyIsAMutation() {
        List<Lot> oldLots = List.of(lot("2024-01-10", 10, "100"));
        List<Lot> newLots = List.of(lot("2024-01-10", 11, "100"));

        assertThat(LotMutationDetector.isExistingLotMutation(oldLots, newLots)).isTrue();
    }

    @Test
    void changingAnExistingLotPriceIsAMutation() {
        List<Lot> oldLots = List.of(lot("2024-01-10", 10, "100"));
        List<Lot> newLots = List.of(lot("2024-01-10", 10, "101"));

        assertThat(LotMutationDetector.isExistingLotMutation(oldLots, newLots)).isTrue();
    }

    @Test
    void identicalListsAreNotAMutation() {
        List<Lot> oldLots = List.of(lot("2024-01-10", 10, "100"), lot("2024-02-01", 5, "200"));
        List<Lot> newLots = List.of(lot("2024-01-10", 10, "100"), lot("2024-02-01", 5, "200"));

        assertThat(LotMutationDetector.isExistingLotMutation(oldLots, newLots)).isFalse();
    }

    @Test
    void emptyOldListIsNotAMutation() {
        List<Lot> newLots = List.of(lot("2024-01-10", 10, "100"));

        assertThat(LotMutationDetector.isExistingLotMutation(List.of(), newLots))
                .isFalse();
    }

    @Test
    void reorderedSameMultisetIsNotAMutation() {
        List<Lot> oldLots = List.of(lot("2024-01-10", 10, "100"), lot("2024-02-01", 5, "200"));
        List<Lot> newLots = List.of(lot("2024-02-01", 5, "200"), lot("2024-01-10", 10, "100"));

        assertThat(LotMutationDetector.isExistingLotMutation(oldLots, newLots)).isFalse();
    }

    @Test
    void duplicateOldLotPartiallyRemovedIsAMutation() {
        // Two identical lots in old, only one survives in new -> multiset shrink is a mutation.
        List<Lot> oldLots = List.of(lot("2024-01-10", 10, "100"), lot("2024-01-10", 10, "100"));
        List<Lot> newLots = List.of(lot("2024-01-10", 10, "100"));

        assertThat(LotMutationDetector.isExistingLotMutation(oldLots, newLots)).isTrue();
    }
}
