package de.regelsuche.graph;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.transform.RecordedExecution;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphEdgePersistenceTest {
    @Test void retainsAllEdgePropertiesAndRejectsSubstitutedProvenance() {
        var transformation = new Transformation("r", "x", RewriteKind.SIMPLIFY, false, -2, true,
            "at-root", List.of("a != 0"), "pack", "PROJECT", List.of("r1", "r2"));
        var execution = RecordedExecution.capture("x + 0 + 0", List.of(transformation));
        var edge = new GraphEdge("x + 0 + 0", "x", "r", 2, 7, "path", "canonical", 10, 3,
            RewriteKind.SIMPLIFY, false, -2, true, CandidateProofStatus.OBSERVED, null, execution);
        var properties = GraphEdgePersistence.properties(edge);
        assertEquals(edge, GraphEdgePersistence.read(edge.fromExpression(), edge.toExpression(), properties));
        properties.put("executionIdentity", "sha256:" + "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> GraphEdgePersistence.read(edge.fromExpression(), edge.toExpression(), properties));
    }
}
