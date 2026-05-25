package de.regelsuche.api.searchgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchGraphFilterTest {

    @Test
    void bestPathFilterKeepsOnlyBestNodesAndSuccessEdges() {
        SearchGraphDto graph = sampleGraph();
        SearchGraphDto filtered = SearchGraphFilter.parse("bestPath=true").apply(graph);
        assertTrue(filtered.nodes().stream().allMatch(SearchGraphNodeDto::isBest));
        assertTrue(filtered.edges().stream()
            .allMatch(e -> e.pathIds().stream().anyMatch(id -> id.startsWith("success:"))));
    }

    @Test
    void hideDeadEndsRemovesThem() {
        SearchGraphDto graph = sampleGraph();
        SearchGraphDto filtered = SearchGraphFilter.parse("hideDeadEnds=true").apply(graph);
        assertTrue(filtered.nodes().stream().noneMatch(SearchGraphNodeDto::isDeadEnd));
    }

    @Test
    void ruleFilterKeepsMatchingEdges() {
        SearchGraphDto filtered = SearchGraphFilter.parse("rule=rule-keep").apply(sampleGraph());
        assertEquals(1, filtered.edges().size());
        assertEquals("rule-keep", filtered.edges().get(0).ruleId());
    }

    @Test
    void emptyFilterIsIdentity() {
        SearchGraphDto graph = sampleGraph();
        SearchGraphDto filtered = SearchGraphFilter.passThrough().apply(graph);
        assertEquals(graph.nodes().size(), filtered.nodes().size());
        assertEquals(graph.edges().size(), filtered.edges().size());
    }

    @Test
    void clusterFilterRestrictsNodes() {
        SearchGraphDto graph = sampleGraph();
        SearchGraphDto filtered = SearchGraphFilter.parse("cluster=c1").apply(graph);
        assertFalse(filtered.nodes().isEmpty());
        assertTrue(filtered.clusters().stream().allMatch(c -> c.id().equals("c1")));
    }

    private static SearchGraphDto sampleGraph() {
        SearchGraphNodeDto best = new SearchGraphNodeDto(
            "a", "a", "a", 5, 0, 1, true, false, CandidateProofStatus.OBSERVED, "c1"
        );
        SearchGraphNodeDto interior = new SearchGraphNodeDto(
            "b", "b", "b", 3, 1, 1, true, false, CandidateProofStatus.OBSERVED, "c1"
        );
        SearchGraphNodeDto dead = new SearchGraphNodeDto(
            "d", "d", "d", 4, 1, 1, false, true, CandidateProofStatus.OBSERVED, "c2"
        );
        SearchGraphEdgeDto bestEdge = new SearchGraphEdgeDto(
            "a", "b", "rule-keep", RewriteKind.SIMPLIFY, -2,
            List.of(), List.of("success:a->b"), true
        );
        SearchGraphEdgeDto deadEdge = new SearchGraphEdgeDto(
            "a", "d", "rule-other", RewriteKind.NORMALIZE, 0,
            List.of(), List.of("explored:a->d"), true
        );
        SearchGraphClusterDto cluster1 = new SearchGraphClusterDto(
            "c1", "c1", ClusterType.RULE_USAGE, List.of("a", "b"), List.of(), 1.0
        );
        SearchGraphClusterDto cluster2 = new SearchGraphClusterDto(
            "c2", "c2", ClusterType.RULE_USAGE, List.of("d"), List.of(), 1.0
        );
        SearchGraphStatsDto stats = new SearchGraphStatsDto(
            3, 2, 1, 3, 1.5, 1, Map.of("rule-keep", 1, "rule-other", 1),
            List.of("rule-keep"), 0, 0
        );
        return new SearchGraphDto(
            List.of(best, interior, dead),
            List.of(bestEdge, deadEdge),
            List.of(cluster1, cluster2),
            stats
        );
    }
}
