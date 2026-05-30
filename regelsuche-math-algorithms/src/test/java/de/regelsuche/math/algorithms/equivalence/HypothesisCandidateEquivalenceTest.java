package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.Transformation;
import java.util.List;
import org.junit.jupiter.api.Test;

class HypothesisCandidateEquivalenceTest {
    private final PolynomialNormalFormEquivalenceService equivalence =
        new PolynomialNormalFormEquivalenceService(new DefaultMathematicalAlgorithmRegistry());

    @Test
    void generatedDifferenceOfSquaresCandidatesAreAlgebraicallyEquivalent() {
        DifferenceOfSquaresPreparationOperator operator = new DifferenceOfSquaresPreparationOperator();

        List<Transformation> candidates = operator.generateCandidates("x^4 + 4");

        assertFalse(candidates.isEmpty());
        assertTrue(candidates.stream().allMatch(candidate ->
            candidate.equivalencePreservingByConstruction()
                && equivalence.arePolynomiallyEquivalent("x^4 + 4", candidate.transformedExpression())));
    }

    @Test
    void equivalenceInfrastructureRejectsFalsePreparationCandidate() {
        assertFalse(equivalence.arePolynomiallyEquivalent("x^4 + 4", "(x ^ 2 + 2) ^ 2 - x ^ 2"));
    }
}
