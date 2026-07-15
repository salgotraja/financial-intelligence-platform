package dev.engnotes.insight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GroupIdGeneratorTest {

    @Test
    void sameMembersRegardlessOfInputOrderProduceTheSameId() {
        assertThat(GroupIdGenerator.groupId(List.of("RELIANCE.NS", "TCS.NS", "INFY.NS")))
                .isEqualTo(GroupIdGenerator.groupId(List.of("TCS.NS", "INFY.NS", "RELIANCE.NS")));
    }

    @Test
    void idIsStableAcrossRepeatedCalls() {
        List<String> members = List.of("HDFCBANK.NS", "^NSEBANK");

        assertThat(GroupIdGenerator.groupId(members)).isEqualTo(GroupIdGenerator.groupId(members));
    }

    @Test
    void differentMembershipProducesADifferentId() {
        assertThat(GroupIdGenerator.groupId(List.of("A", "B")))
                .isNotEqualTo(GroupIdGenerator.groupId(List.of("A", "B", "C")));
    }
}
