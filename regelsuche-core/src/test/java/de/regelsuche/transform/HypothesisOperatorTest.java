package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HypothesisOperatorTest {
    @Test
    void differenceOfSquaresPreparationGeneratesOrdinaryTransformations() {
        DifferenceOfSquaresPreparationOperator operator = new DifferenceOfSquaresPreparationOperator();

        List<Transformation> candidates = operator.generateCandidates("x^4 + 4");

        assertFalse(candidates.isEmpty());
        assertTrue(candidates.stream().allMatch(candidate ->
            DifferenceOfSquaresPreparationOperator.RULE_ID.equals(candidate.rule())));
        assertTrue(candidates.stream().allMatch(Transformation::equivalencePreservingByConstruction));
        assertTrue(candidates.stream().anyMatch(candidate ->
            SquareDifferenceAstPredicate.containsSquareDifference(candidate.transformedExpression())
                && candidate.transformedExpression().contains("2 * x")));
    }

    @Test
    void doesNotGeneratePairCandidateWhenAdditionalAddendsExist() {
        DifferenceOfSquaresPreparationOperator operator = new DifferenceOfSquaresPreparationOperator();

        List<Transformation> candidates = operator.generateCandidates("x^4 + 4 + y^2");

        assertTrue(candidates.isEmpty());
    }

    @Test
    void doesNotGenerateCandidatesForArbitraryNonSquareSums() {
        DifferenceOfSquaresPreparationOperator operator = new DifferenceOfSquaresPreparationOperator();

        assertTrue(operator.generateCandidates("x^4 + 5").isEmpty());
        assertTrue(operator.generateCandidates("x^4 + y").isEmpty());
    }

    @Test
    void respectsConfiguredCandidateBound() {
        DifferenceOfSquaresPreparationOperator operator = new DifferenceOfSquaresPreparationOperator(0);

        assertTrue(operator.generateCandidates("x^4 + 4").isEmpty());
    }

    @Test
    void emitsDeterministicCandidateOrder() {
        DifferenceOfSquaresPreparationOperator operator = new DifferenceOfSquaresPreparationOperator();

        List<String> first = operator.generateCandidates("x^4 + 4").stream()
            .map(Transformation::transformedExpression)
            .toList();
        List<String> second = operator.generateCandidates("x^4 + 4").stream()
            .map(Transformation::transformedExpression)
            .toList();

        assertEquals(first, second);
    }
}
