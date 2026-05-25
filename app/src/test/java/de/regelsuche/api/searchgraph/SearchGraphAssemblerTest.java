package de.regelsuche.api.searchgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.graph.GraphEdge;
import de.regelsuche.graph.GraphSnapshot;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.search.SimplificationSuccess;
import de.regelsuche.transform.RewriteKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchGraphAssemblerTest {

    @Test
    void buildsSearchGraphDto() {
        GraphSnapshot snapshot = new GraphSnapshot(
            List.of("x + 0", "x"),
            List.of(edge("x + 0", "x", "remove_zero", 0, 2, 5, 3))
        );
        List<SimplificationSuccess> successes = List.of(
            new SimplificationSuccess("x + 0", "x", "remove_zero", 0, 2, Instant.now())
        );

        SearchGraphDto dto = new SearchGraphAssembler().assemble(snapshot, successes);

        assertNotNull(dto.stats());
        assertEquals(2, dto.nodes().size());
        assertEquals(1, dto.edges().size());

        SearchGraphNodeDto rootNode = dto.nodes().stream()
            .filter(n -> n.expression().equals("x + 0"))
            .findFirst().orElseThrow();
        assertFalse(rootNode.latex().isBlank());
        SearchGraphEdgeDto edge = dto.edges().getFirst();
        assertEquals("remove_zero", edge.ruleId());
        assertEquals(-2, edge.scoreDelta());
        assertTrue(edge.equivalencePreserving());
    }

    @Test
    void marksBestPathInGraph() {
        // Two-step best path: a -> b (great), b -> c (small extra), plus a side dead-end a -> d
        GraphSnapshot snapshot = new GraphSnapshot(
            List.of("a", "b", "c", "d"),
            List.of(
                edge("a", "b", "rule1", 0, 3, 10, 7),
                edge("b", "c", "rule2", 1, 1, 7, 6),
                edge("a", "d", "rule3", 0, 0, 10, 10) // no improvement -> dead end
            )
        );
        List<SimplificationSuccess> successes = List.of(
            // "best" reflects total improvement; we record the terminal as c
            new SimplificationSuccess("a", "c", "rule2", 2, 4, Instant.now())
        );

        SearchGraphDto dto = new SearchGraphAssembler().assemble(snapshot, successes);

        assertTrue(nodeFor(dto, "a").isBest());
        assertTrue(nodeFor(dto, "b").isBest());
        assertTrue(nodeFor(dto, "c").isBest());
        assertFalse(nodeFor(dto, "d").isBest());
        assertTrue(nodeFor(dto, "d").isDeadEnd(), "d has no improving outgoing edge");
        // c is the success terminal -> not a dead end even though it has no outgoing
        assertFalse(nodeFor(dto, "c").isDeadEnd(), "success terminal must not be marked dead-end");
    }

    @Test
    void computesSearchGraphStats() {
        GraphSnapshot snapshot = new GraphSnapshot(
            List.of("a", "b", "c", "d"),
            List.of(
                edge("a", "b", "rule1", 0, 3, 10, 7),
                edge("b", "c", "rule1", 1, 1, 7, 6),
                edge("a", "d", "rule2", 0, 0, 10, 10)
            )
        );
        List<SimplificationSuccess> successes = List.of(
            new SimplificationSuccess("a", "c", "rule1", 2, 4, Instant.now())
        );

        SearchGraphStatsDto stats = SearchGraphStatsService.compute(snapshot, successes);

        assertEquals(4, stats.nodesVisited());
        assertEquals(3, stats.edgesGenerated());
        assertEquals(1, stats.maxDepthReached());
        assertEquals(4, stats.bestScore());
        assertEquals(2, stats.ruleUsageFrequency().get("rule1"));
        assertEquals(1, stats.ruleUsageFrequency().get("rule2"));
        // rule1 contributes the most improvement -> first
        assertEquals("rule1", stats.mostUsefulRules().getFirst());
        // averageBranchingFactor: 3 edges / 2 fromNodes = 1.5
        assertEquals(1.5, stats.averageBranchingFactor(), 1e-9);
        // d is a dead end; c is a success terminal -> only one dead end
        assertEquals(1, stats.deadEnds());
    }

    private static SearchGraphNodeDto nodeFor(SearchGraphDto dto, String expr) {
        return dto.nodes().stream()
            .filter(n -> n.expression().equals(expr))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing node " + expr));
    }

    private static GraphEdge edge(String from, String to, String rule, int depth, int improvement,
                                  int scoreBefore, int scoreAfter) {
        return new GraphEdge(
            from,
            to,
            rule,
            depth,
            improvement,
            from + "#" + depth,
            "h-" + from + "-" + to,
            scoreBefore,
            scoreAfter,
            RewriteKind.SIMPLIFY,
            false,
            scoreAfter - scoreBefore,
            true,
            CandidateProofStatus.OBSERVED
        );
    }
}
