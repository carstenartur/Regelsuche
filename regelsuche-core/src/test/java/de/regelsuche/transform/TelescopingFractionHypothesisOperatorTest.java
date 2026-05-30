package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TelescopingFractionHypothesisOperatorTest {
    private final TelescopingFractionHypothesisOperator operator = new TelescopingFractionHypothesisOperator();

    @Test
    void decomposesUnitStepFractionsWithoutHardcodedVariables() {
        List<Transformation> candidates = operator.generateCandidates("1 / (n * (n + 1))");

        assertEquals(1, candidates.size());
        Transformation candidate = candidates.getFirst();
        assertEquals(TelescopingFractionHypothesisOperator.RULE_ID, candidate.rule());
        assertEquals("1 / n - 1 / (n + 1)", candidate.transformedExpression());
        assertTrue(TelescopingDifferenceAstPredicate.containsTelescopingDifference(candidate.transformedExpression()));
        assertTrue(FractionDecompositionAstPredicate.containsFractionDecomposition(candidate.transformedExpression()));
    }

    @Test
    void handlesCompoundAdjacentFactorsConservatively() {
        List<Transformation> candidates = operator.generateCandidates("1 / ((x + 2) * (x + 3))");

        assertEquals(1, candidates.size());
        assertEquals("1 / (x + 2) - 1 / (x + 3)", candidates.getFirst().transformedExpression());
    }

    @Test
    void rejectsNearMisses() {
        List<String> nearMisses = List.of(
            "1 / (n * (n + 2))",
            "1 / (n + n + 1)",
            "1 / (n * (m + 1))",
            "1 / (n^2 + 1)"
        );

        for (String expression : nearMisses) {
            assertFalse(operator.generateCandidates(expression).stream()
                .anyMatch(candidate -> TelescopingDifferenceAstPredicate.containsTelescopingDifference(
                    candidate.transformedExpression())), expression);
        }
    }
}
