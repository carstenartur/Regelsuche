package de.regelsuche.paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathComparisonServiceTest {

    @Test
    void identifiesSharedRulesAndShorterPath() {
        DiscoveredTransformation left = transformation("p1", List.of(
            step(0, "a", "b", "r1"),
            step(1, "b", "c", "r2")
        ));
        DiscoveredTransformation right = transformation("p2", List.of(
            step(0, "a", "b", "r1"),
            step(1, "b", "d", "r3"),
            step(2, "d", "c", "r4")
        ));

        PathComparisonDto dto = new PathComparisonService().compare(left, right);

        assertTrue(dto.sharedRules().contains("r1"));
        assertEquals("p1", dto.shorterPath());
        assertTrue(dto.sharedNodes().contains("a"));
        assertTrue(dto.sharedNodes().contains("b"));
        assertTrue(dto.sharedNodes().contains("c"));
        assertEquals(1, dto.leftOnlySteps().size());
        assertEquals(2, dto.rightOnlySteps().size());
    }

    @Test
    void recognisesFewerAssumptions() {
        DiscoveredTransformation left = transformation("p1", List.of(
            step(0, "a", "b", "r1", true)
        ));
        DiscoveredTransformation right = transformation("p2", List.of(
            step(0, "a", "b", "r2", false)
        ));
        PathComparisonDto dto = new PathComparisonService().compare(left, right);
        assertEquals("p1", dto.fewerAssumptionsPath());
        assertEquals(0, dto.leftAssumptionSteps());
        assertEquals(1, dto.rightAssumptionSteps());
    }

    private static DiscoveredTransformation transformation(String id, List<TransformationStep> steps) {
        ExpressionScore score = new ExpressionScore(1, 1, 1, 1, 0);
        return new DiscoveredTransformation(
            id,
            steps.get(0).beforeExpression(),
            steps.get(steps.size() - 1).afterExpression(),
            steps, score, score, 0,
            CandidateProofStatus.OBSERVED,
            Instant.parse("2024-01-01T00:00:00Z"),
            ""
        );
    }

    private static TransformationStep step(int index, String before, String after, String ruleId) {
        return step(index, before, after, ruleId, true);
    }

    private static TransformationStep step(int index, String before, String after, String ruleId, boolean equivalence) {
        return new TransformationStep(index, before, after, ruleId, RewriteKind.NORMALIZE, 5, 4, equivalence, "");
    }
}
