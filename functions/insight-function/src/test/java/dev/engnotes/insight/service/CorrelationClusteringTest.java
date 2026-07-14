package dev.engnotes.insight.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.insight.model.CorrelationEdge;
import java.util.List;
import org.junit.jupiter.api.Test;

class CorrelationClusteringTest {

    @Test
    void directEdgeFormsATwoMemberGroup() {
        var components = CorrelationClustering.connectedComponents(List.of(new CorrelationEdge("B", "A", 0.7)));

        assertThat(components).containsExactly(List.of("A", "B"));
    }

    @Test
    void transitiveEdgesMergeIntoOneGroupEvenWithoutADirectEdge() {
        // A-B and B-C both qualify; A-C was never computed (or fell below threshold) yet A, B, C
        // still land in one group via B.
        var edges = List.of(new CorrelationEdge("A", "B", 0.65), new CorrelationEdge("B", "C", 0.72));

        var components = CorrelationClustering.connectedComponents(edges);

        assertThat(components).hasSize(1);
        assertThat(components.getFirst()).containsExactly("A", "B", "C");
    }

    @Test
    void disjointEdgeSetsFormSeparateGroups() {
        var edges = List.of(new CorrelationEdge("A", "B", 0.7), new CorrelationEdge("C", "D", 0.8));

        var components = CorrelationClustering.connectedComponents(edges);

        assertThat(components).containsExactlyInAnyOrder(List.of("A", "B"), List.of("C", "D"));
    }

    @Test
    void noEdgesProducesNoGroups() {
        assertThat(CorrelationClustering.connectedComponents(List.of())).isEmpty();
    }
}
