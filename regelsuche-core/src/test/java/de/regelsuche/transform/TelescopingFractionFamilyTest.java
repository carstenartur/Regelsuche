package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Family-level tests for {@link TelescopingFractionHypothesisOperator} covering variants, near-misses, and negatives. */
class TelescopingFractionFamilyTest {
    private final TelescopingFractionHypothesisOperator operator = new TelescopingFractionHypothesisOperator();

    @Test
    void positiveFamily_unitStep() {
        assertDecomposesTo("1 / (n * (n + 1))", "1 / n - 1 / (n + 1)");
        assertDecomposesTo("1 / ((x + 2) * (x + 3))", "1 / (x + 2) - 1 / (x + 3)");
        assertDecomposesTo("1 / (m * (m + 1))", "1 / m - 1 / (m + 1)");
    }

    @Test
    void positiveFamily_unitStep_predicate() {
        for (String expression : List.of("1 / (n * (n + 1))", "1 / ((k + 3) * (k + 4))")) {
            List<Transformation> candidates = operator.generateCandidates(expression);
            assertFalse(candidates.isEmpty(), expression);
            assertTrue(candidates.stream().anyMatch(c ->
                TelescopingDifferenceAstPredicate.containsTelescopingDifference(c.transformedExpression())),
                "Expected unit-step predicate match for " + expression);
        }
    }

    @Test
    void positiveFamily_scaledNumerator() {
        List<Transformation> candidates = operator.generateCandidates("2 / (n * (n + 1))");
        assertFalse(candidates.isEmpty(), "scaled numerator should produce a candidate");
        Transformation candidate = candidates.getFirst();
        assertEquals(TelescopingFractionHypothesisOperator.RULE_ID, candidate.rule());
        assertTrue(candidate.transformedExpression().contains("2 / n"), candidate.transformedExpression());
        assertTrue(candidate.transformedExpression().contains("2 / (n + 1)"), candidate.transformedExpression());
    }

    @Test
    void variantFamily_compoundAdjacentFactors() {
        assertDecomposesTo("1 / ((x + 2) * (x + 3))", "1 / (x + 2) - 1 / (x + 3)");
        assertDecomposesTo("1 / ((k + 3) * (k + 4))", "1 / (k + 3) - 1 / (k + 4)");
        assertDecomposesTo("1 / ((n + 1) * (n + 2))", "1 / (n + 1) - 1 / (n + 2)");
    }

    @Test
    void nearMissFamily_sumNotProduct() {
        assertFalse(operator.generateCandidates("1 / (n + n + 1)").stream()
            .anyMatch(c -> TelescopingDifferenceAstPredicate.containsTelescopingDifference(c.transformedExpression())),
            "1/(n+n+1) must not produce a telescoping-difference candidate");
    }

    @Test
    void nearMissFamily_differentSymbols() {
        assertFalse(operator.generateCandidates("1 / (n * (m + 1))").stream()
            .anyMatch(c -> TelescopingDifferenceAstPredicate.containsTelescopingDifference(c.transformedExpression())),
            "1/(n*(m+1)) with different symbols must not produce a telescoping-difference candidate");
    }

    @Test
    void nearMissFamily_nonAdjacentFactors() {
        assertTrue(operator.generateCandidates("1 / (n * (n + 2))").isEmpty(),
            "non-adjacent denominator factors must not produce a candidate");
        assertTrue(operator.generateCandidates("1 / ((n + 1) * (n + 3))").isEmpty(),
            "non-adjacent shifted denominator factors must not produce a candidate");
    }

    @Test
    void nearMissFamily_singleQuadraticTerm() {
        assertTrue(operator.generateCandidates("1 / (n^2 + 1)").isEmpty(),
            "1/(n^2+1) must produce no candidate");
    }

    @Test
    void negativeFamily_threeFactors() {
        assertTrue(operator.generateCandidates("1 / (n * (n + 1) * (n + 2))").isEmpty(),
            "three-factor denominator must be rejected");
    }

    private void assertDecomposesTo(String input, String expected) {
        List<String> candidates = operator.generateCandidates(input).stream()
            .map(Transformation::transformedExpression)
            .toList();
        assertTrue(candidates.contains(expected),
            "Expected '" + expected + "' in " + candidates + " for input '" + input + "'");
    }
}
