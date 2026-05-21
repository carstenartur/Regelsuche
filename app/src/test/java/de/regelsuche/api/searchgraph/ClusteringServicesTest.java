package de.regelsuche.api.searchgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClusteringServicesTest {

    @Test
    void macroSequenceClustererFindsRecurringRuleSequence() {
        DiscoveredTransformation t1 = transformation("p1", List.of(
            step(0, "a", "b", "r1"),
            step(1, "b", "c", "r2"),
            step(2, "c", "d", "r3")
        ));
        DiscoveredTransformation t2 = transformation("p2", List.of(
            step(0, "x", "y", "r1"),
            step(1, "y", "z", "r2"),
            step(2, "z", "w", "r3")
        ));
        List<SearchGraphClusterDto> clusters = new MacroSequenceClusterer().cluster(List.of(t1, t2));
        assertTrue(clusters.stream().anyMatch(c -> c.type() == ClusterType.MACRO_SEQUENCE));
        SearchGraphClusterDto longest = clusters.stream()
            .filter(c -> c.label().contains("r1") && c.label().contains("r2") && c.label().contains("r3"))
            .findFirst()
            .orElseThrow();
        assertEquals(2, longest.supportingPathIds().size());
        assertTrue(longest.cohesionScore() > 0);
    }

    @Test
    void structuralClustererGroupsBySkeleton() {
        StructuralExpressionClusterer clusterer = new StructuralExpressionClusterer();
        List<SearchGraphClusterDto> clusters = clusterer.cluster(List.of(
            "(x + 1) * (x + 1)",
            "(y + 2) * (y + 2)",
            "sin(z)"
        ));
        assertTrue(clusters.size() >= 1);
        SearchGraphClusterDto cluster = clusters.get(0);
        assertEquals(ClusterType.STRUCTURAL_PATTERN, cluster.type());
        assertEquals(2, cluster.nodeIds().size());
        assertNotNull(cluster.label());
        assertTrue(cluster.label().startsWith("skeleton:"));
    }

    @Test
    void structuralSkeletonIgnoresLiterals() {
        StructuralExpressionClusterer clusterer = new StructuralExpressionClusterer();
        assertEquals(clusterer.skeleton("(x + 1) * (x + 1)"), clusterer.skeleton("(y + 2) * (y + 2)"));
    }

    private static DiscoveredTransformation transformation(String id, List<TransformationStep> steps) {
        ExpressionScore score = new ExpressionScore(1, 1, 1, 1, 0);
        return new DiscoveredTransformation(
            id,
            steps.get(0).beforeExpression(),
            steps.get(steps.size() - 1).afterExpression(),
            steps,
            score,
            score,
            0,
            CandidateProofStatus.OBSERVED,
            Instant.parse("2024-01-01T00:00:00Z"),
            ""
        );
    }

    private static TransformationStep step(int index, String before, String after, String ruleId) {
        return new TransformationStep(index, before, after, ruleId, RewriteKind.NORMALIZE, 5, 4, true, "");
    }
}
