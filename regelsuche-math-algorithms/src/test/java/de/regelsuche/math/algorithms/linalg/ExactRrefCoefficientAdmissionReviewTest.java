package de.regelsuche.math.algorithms.linalg;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactRrefCoefficientAdmissionReviewTest {
    @Test void insufficientIntermediateBitRoomRefusesBeforeActualBigIntegerMultiplication() {
        var raw = new ExactRrefSolver.CoefficientSystem(
            new ExactLinearSystem.ExactMatrix(List.of(List.of(new Rational(new MultiplicationProbe("7"), BigInteger.ONE)))),
            List.of("coefficient"), new ExactLinearSystem.ExactVector(List.of(Rational.of(6))),
            List.of(new ExactLinearSystem.RowOrigin(0, "seven times coefficient equals six")));
        var solver = new ExactRrefSolver();
        MultiplicationProbe.calls = 0;
        MultiplicationProbe.refuse = true;
        try {
            var bounded = solver.solveCoefficients(raw, new Budget(10_000), 3);
            assertEquals(ExactRrefSolver.Status.BUDGET_INCONCLUSIVE, bounded.status());
            assertEquals("RREF_COEFFICIENT_BIT_BUDGET_EXHAUSTED", bounded.detailCode());
            assertTrue(bounded.work().consumedWorkUnits() > 2, "the two input scalars fit before elimination starts");
            assertTrue(bounded.reduction().isEmpty());
            assertEquals(0, MultiplicationProbe.calls);
        } finally {
            MultiplicationProbe.refuse = false;
        }
        var admitted = solver.solveCoefficients(raw, new Budget(10_000), 16);
        assertEquals(ExactRrefSolver.Status.SOLVED, admitted.status());
        assertTrue(MultiplicationProbe.calls > 0, "the same admitted real algorithm must enter the probe");
        assertEquals(new Rational(BigInteger.valueOf(6), BigInteger.valueOf(7)),
            admitted.reduction().orElseThrow().particularSolution().orElseThrow().get(0));
        assertTrue(solver.verifyCoefficients(raw, admitted, 16));
    }

    private static final class MultiplicationProbe extends BigInteger {
        private static int calls;
        private static boolean refuse;
        private MultiplicationProbe(String value) { super(value); }
        private MultiplicationProbe(byte[] value) { super(value); }
        @Override public BigInteger divide(BigInteger divisor) {
            return new MultiplicationProbe(super.divide(divisor).toByteArray());
        }
        @Override public BigInteger multiply(BigInteger other) {
            calls++;
            if (refuse) throw new AssertionError("large arithmetic started before bit admission");
            return super.multiply(other);
        }
    }
}
