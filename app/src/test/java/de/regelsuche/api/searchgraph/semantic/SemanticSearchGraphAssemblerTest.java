package de.regelsuche.api.searchgraph.semantic;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.api.searchgraph.SearchGraphAssembler;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.graph.GraphSnapshot;
import de.regelsuche.search.SimplificationSuccess;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticSearchGraphAssemblerTest {

    @Test
    void collapsesLowSignalNormalizationEdges() {
        GraphSnapshot snapshot = new GraphSnapshot(
            List.of("a+b", "b+a", "a"),
            List.of(
                new GraphEdge("a+b", "b+a", "commutativity", 0, 0, "p#0", "", 5, 5,
                    RewriteKind.NORMALIZE, false, 0, true, CandidateProofStatus.OBSERVED),
                new GraphEdge("b+a", "a", "remove_zero", 1, 2, "p#1", "", 5, 3,
                    RewriteKind.SIMPLIFY, false, -2, true, CandidateProofStatus.OBSERVED)
            )
        );
        var raw = new SearchGraphAssembler().assemble(snapshot,
            List.of(new SimplificationSuccess("a+b", "a", "remove_zero", 1, 2, Instant.now())));
        var semantic = new SemanticSearchGraphAssembler().assemble(
            raw,
            List.of(),
            List.of(),
            SemanticGraphViewMode.SEMANTIC,
            false,
            true,
            false,
            12,
            8
        );
        assertTrue(semantic.edges().stream().anyMatch(e -> e.kind() == SemanticEdgeKind.LOW_SIGNAL_COLLAPSED));
    }
}
