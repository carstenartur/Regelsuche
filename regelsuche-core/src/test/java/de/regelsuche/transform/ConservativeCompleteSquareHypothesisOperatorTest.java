package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConservativeCompleteSquareHypothesisOperatorTest {
    private final ConservativeCompleteSquareHypothesisOperator operator = new ConservativeCompleteSquareHypothesisOperator();

    @Test
    void positiveCandidatesAreBoundedAndDeterministic() {
        List<Transformation> first = operator.generateCandidates("x^2 + 6*x + 5");
        List<Transformation> second = operator.generateCandidates("x^2 + 6*x + 5");

        assertFalse(first.isEmpty());
        assertTrue(first.size() <= 6);
        assertEquals(first, second);
        assertTrue(first.stream().anyMatch(candidate ->
            candidate.rule().equals(ConservativeCompleteSquareHypothesisOperator.RULE_ID)
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
    void optInFactorMatchingRecognizesSquareBasesContainingProducts() throws Exception {
        var constructor = ConservativeCompleteSquareHypothesisOperator.class.getConstructor(int.class, boolean.class);
        var extended = constructor.newInstance(6, true);
        String input = "(x^2)^2+(2*y^2)^2+2*(x^2)*(2*y^2)";
        assertTrue(operator.generateCandidates(input).isEmpty(), "legacy matching stays unchanged");
        assertFalse(extended.generateCandidates(input).isEmpty(), "factorized square bases form a perfect square");
        assertFalse(extended.generateCandidates("(a+1)^2+(3*b)^2+6*b*(a+1)").isEmpty());
        assertTrue(extended.generateCandidates("(a+1)^2+(3*b)^2+6*b*(a-1)").isEmpty());
        assertTrue(extended.generateCandidates("(a+1)^2+(3*b)^2+5*b*(a+1)").isEmpty());
    }

    @Test
    void negativeCasesProduceNoFalsePositive() {
        assertTrue(operator.generateCandidates("x^2 + 6*x + 6").isEmpty());
        assertTrue(operator.generateCandidates("x^2 + 6*x + y").isEmpty());
        assertTrue(operator.generateCandidates("x^2 + 2*x*y + z^2").isEmpty());
    }
}
