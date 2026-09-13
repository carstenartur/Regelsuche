package de.regelsuche.math.algorithms.linalg;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.RowOrigin;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.SolutionClassification;
import de.regelsuche.math.algorithms.linalg.ExactRrefSolver.CoefficientSystem;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ExactRrefCoefficientSystemTest {
    private final ExactRrefSolver solver = new ExactRrefSolver();

    @Test
    void rawAdmissionDerivesRanksAndPreservesTheExistingExactResult() {
        var raw = system(List.of(List.of(Rational.of(2), Rational.ONE), List.of(Rational.ONE, Rational.NEGATIVE_ONE)),
            List.of(Rational.of(5), Rational.ONE));
        var original = new ExactLinearSystem(raw.coefficients(), raw.variables(), raw.rightHandSide(), raw.rowOrigins(),
            2, 2, SolutionClassification.UNIQUE);
        var bounded = solver.solveCoefficients(raw, new Budget(10_000), 512);
        var legacy = solver.solve(original, new Budget(10_000));
        assertEquals(legacy.reduction(), bounded.reduction());
        assertEquals(legacy.certificate(), bounded.certificate());
        assertTrue(solver.verify(original, legacy));
        assertTrue(solver.verifyCoefficients(raw, bounded, 512));
        assertFalse(solver.verifyCoefficients(raw, bounded, 1));
        assertTrue(bounded.work().consumedWorkUnits() > legacy.work().consumedWorkUnits(),
            "new raw admission must also charge its guarded and constructor solution back-substitution checks");
    }

    @Test
    void rejectsRawDimensionsAndMismatchedOriginsBeforeElimination() {
        assertThrows(IllegalArgumentException.class, () -> new CoefficientSystem(
            new ExactMatrix(List.of(java.util.Collections.nCopies(13, Rational.ONE))),
            IntStream.range(0, 13).mapToObj(index -> "x" + index).toList(), new ExactVector(List.of(Rational.ONE)),
            List.of(new RowOrigin(0, "oversized"))));
        assertThrows(IllegalArgumentException.class, () -> new CoefficientSystem(
            new ExactMatrix(java.util.Collections.nCopies(129, List.of(Rational.ONE))), List.of("x"),
            new ExactVector(java.util.Collections.nCopies(129, Rational.ONE)),
            IntStream.range(0, 129).mapToObj(index -> new RowOrigin(index, "oversized")).toList()));
        assertThrows(IllegalArgumentException.class, () -> new CoefficientSystem(new ExactMatrix(List.of(List.of(Rational.ONE))),
            List.of("x"), new ExactVector(List.of(Rational.ONE)), List.of()));
    }

    @Test
    void boundsBothAdmittedScalarsAndIntermediateArithmetic() {
        var input = solver.solveCoefficients(system(List.of(List.of(Rational.of(257))), List.of(Rational.ONE)),
            new Budget(10_000), 8);
        assertEquals(ExactRrefSolver.Status.BUDGET_INCONCLUSIVE, input.status());
        assertTrue(input.reduction().isEmpty());
        var growing = system(List.of(List.of(Rational.of(7), Rational.of(6)), List.of(Rational.of(5), Rational.of(7))),
            List.of(Rational.ONE, Rational.of(2)));
        var bounded = solver.solveCoefficients(growing, new Budget(10_000), 3);
        assertEquals(ExactRrefSolver.Status.BUDGET_INCONCLUSIVE, bounded.status());
        assertEquals("RREF_COEFFICIENT_BIT_BUDGET_EXHAUSTED", bounded.detailCode());
        assertTrue(bounded.work().consumedWorkUnits() > 6, "the initial six matrix entries fit; elimination must trigger the bound");
        assertEquals(ExactRrefSolver.Status.SOLVED, solver.solveCoefficients(growing, new Budget(10_000), 512).status());
    }

    private static CoefficientSystem system(List<List<Rational>> coefficients, List<Rational> rhs) {
        return new CoefficientSystem(new ExactMatrix(coefficients), IntStream.range(0, coefficients.getFirst().size())
            .mapToObj(index -> "x" + index).toList(), new ExactVector(rhs),
            IntStream.range(0, rhs.size()).mapToObj(index -> new RowOrigin(index, "public coefficient row " + index)).toList());
    }
}
