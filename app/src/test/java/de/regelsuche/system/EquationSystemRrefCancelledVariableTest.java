package de.regelsuche.system;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem;
import java.util.List;
import org.junit.jupiter.api.Test;

class EquationSystemRrefCancelledVariableTest {

    @Test
    void cancelledCoordinateRemainsAFreeVariableWithExactNullspaceBasis() {
        EquationSystemRepresentationService.Analysis analysis =
            new EquationSystemRepresentationService().analyze("x - x = 0");

        assertEquals(List.of("x"),
            analysis.exactSystem().orElseThrow().variables());
        assertEquals(
            ExactLinearSystem.SolutionClassification.UNDERDETERMINED,
            analysis.rref().orElseThrow().solutionClassification());
        assertEquals(List.of(0),
            analysis.rref().orElseThrow().freeVariableColumns());
        assertEquals(List.of(Rational.ZERO),
            analysis.rref().orElseThrow()
                .particularSolution().orElseThrow().values());
        assertEquals(
            List.of(List.of(Rational.ONE)),
            analysis.rref().orElseThrow().nullspaceBasis().stream()
                .map(vector -> vector.values())
                .toList());
    }

    @Test
    void cancelledCoordinateWithNonZeroRhsProducesContradictionWitness() {
        EquationSystemRepresentationService.Analysis analysis =
            new EquationSystemRepresentationService().analyze("x - x = 1");

        assertEquals(List.of("x"),
            analysis.exactSystem().orElseThrow().variables());
        assertEquals(
            ExactLinearSystem.SolutionClassification.INCONSISTENT,
            analysis.rref().orElseThrow().solutionClassification());
        assertEquals(List.of(0),
            analysis.rref().orElseThrow().contradictionRows());
        assertEquals(
            List.of(Rational.ZERO, Rational.ONE),
            analysis.rref().orElseThrow().reducedAugmentedRows().getFirst());
    }
}
