package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RationalizationHypothesisOperatorTest {
    private final RationalizationHypothesisOperator operator = new RationalizationHypothesisOperator();

    @Test
    void rationalizesSqrtPlusConstantAndRecordsAssumption() {
        List<Transformation> candidates = operator.generateCandidates("1 / (sqrt(x) + 1)");

        assertEquals(1, candidates.size());
        Transformation candidate = candidates.getFirst();
        assertEquals(RationalizationHypothesisOperator.RULE_ID, candidate.rule());
        assertEquals("(sqrt(x) - 1) / (x - 1)", candidate.transformedExpression());
        assertTrue(candidate.applicationKey().contains("assumption:x != 1"));
        assertTrue(RationalizedDenominatorAstPredicate.hasRationalizedDenominator(candidate.transformedExpression()));
    }

    @Test
    void rationalizesSqrtMinusConstantAndRecordsAssumption() {
        List<Transformation> candidates = operator.generateCandidates("1 / (sqrt(x) - 1)");

        assertEquals(1, candidates.size());
        assertEquals("(sqrt(x) + 1) / (x - 1)", candidates.getFirst().transformedExpression());
        assertTrue(candidates.getFirst().applicationKey().contains("assumption:x != 1"));
    }

    @Test
    void rejectsNearMissesWithoutClearConservativeRootStructure() {
        List<String> nearMisses = List.of(
            "1 / (sqrt(x) + sqrt(y))",
            "1 / (x + 1)",
            "1 / (sqrt(x) + y)"
        );

        for (String expression : nearMisses) {
            assertFalse(operator.generateCandidates(expression).stream()
                .anyMatch(candidate -> RationalizedDenominatorAstPredicate.hasRationalizedDenominator(
                    candidate.transformedExpression())), expression);
        }
    }
}
