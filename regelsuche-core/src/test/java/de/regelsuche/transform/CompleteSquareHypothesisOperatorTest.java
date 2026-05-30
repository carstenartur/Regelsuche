package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CompleteSquareHypothesisOperatorTest {
    private final CompleteSquareHypothesisOperator operator = new CompleteSquareHypothesisOperator();

    @Test
    void positiveCandidatesAreBoundedAndDeterministic() {
        List<Transformation> first = operator.generateCandidates("x^2 + 6*x + 5");
        List<Transformation> second = operator.generateCandidates("x^2 + 6*x + 5");

        assertFalse(first.isEmpty());
        assertTrue(first.size() <= 6);
        assertEquals(first, second);
        assertTrue(first.stream().anyMatch(candidate ->
            candidate.rule().equals(CompleteSquareHypothesisOperator.RULE_ID)
                && SquareDifferenceAstPredicate.containsSquareDifference(candidate.transformedExpression())));
    }

    @Test
    void supportsBivariatePerfectSquares() {
        List<Transformation> candidates = operator.generateCandidates("x^2 + 2*x*y + y^2");

        assertFalse(candidates.isEmpty());
        assertTrue(candidates.stream().anyMatch(candidate ->
            PerfectSquareAstPredicate.containsPerfectSquare(candidate.transformedExpression())));
    }

    @Test
    void supportsCompoundBaseWhenStructurallyVisible() {
        List<Transformation> candidates = operator.generateCandidates("(x + 1)^2 + 6*(x + 1) + 5");

        assertFalse(candidates.isEmpty());
        assertTrue(candidates.stream().anyMatch(candidate ->
            SquareDifferenceAstPredicate.containsSquareDifference(candidate.transformedExpression())));
    }

    @Test
    void negativeCasesProduceNoFalsePositive() {
        assertTrue(operator.generateCandidates("x^2 + 6*x + 6").isEmpty());
        assertTrue(operator.generateCandidates("x^2 + 6*x + y").isEmpty());
        assertTrue(operator.generateCandidates("x^2 + 2*x*y + z^2").isEmpty());
    }
}
