package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SearchTraceCollectorTest {
    @Test
    void collectorSortsNodeTagsAndEdgeMetadataDeterministically() {
        SearchState root = new SearchState(
            "x + x",
            0,
            new ExpressionScore(3, 2, 1, 1, 0),
            List.of("x + x"),
            List.of(),
            Set.of(),
            0,
            "root",
            null,
            null,
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            0
        );
        SearchState child = new SearchState(
            "2 * x",
            1,
            new ExpressionScore(3, 2, 1, 1, 1),
            List.of("x + x", "2 * x"),
            List.of("macro_rule"),
            Set.of("macro_rule:2*x"),
            0,
            "child",
            "x + x",
            "macro_rule",
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            1
        );

        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenario(
            "synthetic",
            "Synthetic",
            "x + x",
            "2 * x",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            null,
            null
        );

        SearchTraceCollector.TraceGraph graph = new SearchTraceCollector().collect(
            scenario,
            new SearchTraceCollector.SearchRunTrace(true, List.of(root, child), List.of("x + x", "2 * x"), List.of("macro_rule")),
            new SearchTraceCollector.SearchRunTrace(false, List.of(root, child), List.of(), List.of()),
            List.of("macro_rule"),
            List.of(),
            Map.of("macro_rule", new ScenarioRule(
                "macro_rule",
                "x + x",
                "2 * x",
                RewriteKind.NORMALIZE,
                0,
                List.of(de.regelsuche.knowledge.SearchEffect.SIMPLIFYING, de.regelsuche.knowledge.SearchEffect.BRIDGING),
                "synthetic-family",
                ScenarioRuleStatus.VALIDATED,
                true,
                List.of("x + x")
            ))
        );

        DiscoveryBenchmarkEvidence.EvidenceNode inputNode = graph.nodes().stream()
            .filter(node -> "input".equals(node.kind()))
            .findFirst()
            .orElseThrow();
        assertEquals(List.of("input", "selected-path"), inputNode.tags());

        DiscoveryBenchmarkEvidence.EvidenceEdge edge = graph.edges().getFirst();
        assertEquals(
            List.of(
                de.regelsuche.knowledge.SearchEffect.BRIDGING,
                de.regelsuche.knowledge.SearchEffect.NORMALIZING,
                de.regelsuche.knowledge.SearchEffect.SIMPLIFYING
            ),
            edge.searchEffect()
        );
        assertEquals(List.of("macro-shortcut", "selected-path"), edge.tags());
    }
}
