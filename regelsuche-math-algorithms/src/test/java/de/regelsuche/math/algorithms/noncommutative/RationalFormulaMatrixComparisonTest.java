package de.regelsuche.math.algorithms.noncommutative;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.*;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaComparison.*;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.Certificate;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Assignment;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Matrix;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static de.regelsuche.scalar.ExactRational.*;
import static org.junit.jupiter.api.Assertions.*;

class RationalFormulaMatrixComparisonTest {
    private static final Variable X = new Variable(10), Y = new Variable(-3);
    private static final Scalar UNIT = new Scalar(ONE), NIL = new Scalar(ZERO);
    private static final RationalFormula H = difference(new Product(X, Y), new Product(Y, X));
    private static final RationalFormula IDENTITY = new Product(new Inverse(H), H);
    private static final Matrix DIAGONAL = new Matrix(2, List.of(ONE, ZERO, ZERO, NEGATIVE_ONE));
    private static final Matrix SWAP = new Matrix(2, List.of(ZERO, ONE, ONE, ZERO));
    private static final Matrix ZERO_TWO = new Matrix(2, List.of(ZERO, ZERO, ZERO, ZERO));
    private static final Assignment CENTER = new Assignment(2, Map.of(10, DIAGONAL, -3, SWAP));
    private static final Assignment ZERO_CENTER = new Assignment(2, Map.of(10, ZERO_TWO, -3, ZERO_TWO));
    private static final IterationLimits LIMITS = new IterationLimits(100_000_000, 1_000_000, 60_000);

    @Test void provesInverseCommutatorIdentityWithoutAnyScalarCenter() {
        var result = RationalFormulaComparison.compare(IDENTITY, UNIT, CENTER, LIMITS, () -> false);
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, result.status(), result.reason());
        var proof = (Invariant) result.evidence().orElseThrow();
        assertEquals(1, proof.binding().left().obligations().size());
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, verify(IDENTITY, UNIT, CENTER, proof));
        var three = threeByThreeAssignment();
        var expected = new Matrix(3, List.of(ONE, ZERO, ZERO, ZERO, ONE, ZERO, ZERO, ZERO, ONE));
        assertEquals(expected, evaluate(IDENTITY, proof.binding().left(), three));
        assertEquals(expected, evaluate(UNIT, proof.binding().right(), three));
        var one = new Matrix(1, List.of(ONE));
        var scalar = RationalFormulaComparison.compare(IDENTITY, UNIT,
            new Assignment(1, Map.of(10, one, -3, one)), LIMITS, () -> false);
        assertEquals(Status.INCONCLUSIVE, scalar.status());
        assertEquals("UNDEFINED_SCALAR_CENTER", scalar.reason());
    }

    @Test void aCoincidenceAtTheMatrixCenterProducesACheckedDistinctWitness() {
        var left = new Inverse(H);
        var right = new Product(new Scalar(NEGATIVE_ONE.divide(integer(4))), H);
        var result = RationalFormulaComparison.compare(left, right, CENTER, LIMITS, () -> false);
        assertEquals(Status.DISTINCT, result.status(), result.reason());
        var witness = (Witness) result.evidence().orElseThrow();
        var half = ONE.divide(integer(2));
        var coincident = new Matrix(2, List.of(ZERO, half.negate(), half, ZERO));
        assertEquals(coincident, evaluate(left, witness.binding().left(), CENTER));
        assertEquals(coincident, evaluate(right, witness.binding().right(), CENTER));
        assertTrue(witness.assignment().order() > 2);
        assertNotEquals(evaluate(left, witness.binding().left(), witness.assignment()),
            evaluate(right, witness.binding().right(), witness.assignment()));
        assertEquals(Status.DISTINCT, verify(left, right, CENTER, witness));
        assertEquals(Status.INVALID_EVIDENCE, verify(left, right, CENTER, new Witness(witness.binding(), CENTER)));
    }

    @Test void aPolynomialIdentityOfTwoByTwoMatricesDoesNotBecomeAUniversalIdentity() {
        var square = new Product(H, H);
        var polynomial = difference(new Product(square, X), new Product(X, square));
        var result = RationalFormulaComparison.compare(polynomial, NIL, ZERO_CENTER, LIMITS, () -> false);
        assertEquals(Status.DISTINCT, result.status(), result.reason());
        var witness = (Witness) result.evidence().orElseThrow();
        assertEquals(ZERO_TWO, evaluate(polynomial, witness.binding().left(), CENTER));
        assertTrue(witness.assignment().order() > 2);
        assertNotEquals(evaluate(polynomial, witness.binding().left(), witness.assignment()),
            evaluate(NIL, witness.binding().right(), witness.assignment()));
        assertEquals(Status.DISTINCT, verify(polynomial, NIL, ZERO_CENTER, witness));
        var two = integer(2);
        var expected = new Matrix(3, List.of(ZERO, ZERO, two, two, ZERO, ZERO, ZERO, two, ZERO));
        assertEquals(expected, evaluate(polynomial, witness.binding().left(), threeByThreeAssignment()));
    }

    @Test void bindsEveryCenterEntryAndRejectsIncompleteOrForeignInvariantEvidence() {
        var result = RationalFormulaComparison.compare(IDENTITY, UNIT, CENTER, LIMITS, () -> false);
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, result.status(), result.reason());
        var proof = (Invariant) result.evidence().orElseThrow();
        var changed = new Assignment(2, Map.of(10, DIAGONAL,
            -3, new Matrix(2, List.of(ZERO, integer(2), ONE, ZERO))));
        assertEquals(CENTER.variables().get(-3).entries().getFirst(), changed.variables().get(-3).entries().getFirst());
        assertEquals(Status.INVALID_EVIDENCE, verify(IDENTITY, UNIT, changed, proof));
        assertEquals(Status.INVALID_EVIDENCE, verify(IDENTITY, NIL, CENTER, proof));
        assertEquals(Status.INVALID_EVIDENCE,
            verify(IDENTITY, UNIT, CENTER, new Invariant(proof.binding(), List.of())));
        assertTrue(proof.columns().size() > 1);
        assertEquals(Status.INVALID_EVIDENCE, verify(IDENTITY, UNIT, CENTER,
            new Invariant(proof.binding(), List.of(proof.columns().getFirst()))));
        assertEquals(Status.INVALID_EVIDENCE, verify(IDENTITY, UNIT, CENTER,
            new Invariant(proof.binding(), List.of(List.of(ZERO)))));
    }

    @Test void originalDomainsAndResourceStopsRemainPartOfTheMatrixComparison() {
        var left = new Product(NIL, new Inverse(H));
        var complete = RationalFormulaComparison.compare(left, NIL, CENTER, LIMITS, () -> false);
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, complete.status(), complete.reason());
        var proof = (Invariant) complete.evidence().orElseThrow();
        assertEquals(1, proof.binding().left().obligations().size());
        var undefined = RationalFormulaComparison.compare(left, NIL, ZERO_CENTER, LIMITS, () -> false);
        assertEquals(Status.INCONCLUSIVE, undefined.status());
        assertEquals("UNDEFINED_MATRIX_CENTER", undefined.reason());
        assertTrue(undefined.evidence().isEmpty());
        var original = proof.binding().left();
        var missingObligation = new Certificate(original.source(), original.pencil(), original.u(), original.v(), List.of());
        var forged = new Invariant(new Binding(missingObligation, proof.binding().right(), CENTER), proof.columns());
        assertEquals(Status.INVALID_EVIDENCE, verify(left, NIL, CENTER, forged));

        var inverse = new Inverse(H);
        var different = RationalFormulaComparison.compare(inverse, NIL, CENTER, LIMITS, () -> false);
        assertEquals(Status.DISTINCT, different.status(), different.reason());
        assertEquals(Status.INVALID_EVIDENCE, verify(inverse, NIL, CENTER,
            new Witness(different.evidence().orElseThrow().binding(), ZERO_CENTER)));
        assertEquals(Status.INCONCLUSIVE, RationalFormulaComparison.compare(left, NIL,
            new Assignment(2, Map.of(10, DIAGONAL)), LIMITS, () -> false).status());
        assertEquals(Status.INCONCLUSIVE, RationalFormulaComparison.compare(left, NIL,
            new Assignment(2, Map.of(10, new Matrix(1, List.of(ONE)), -3, SWAP)), LIMITS, () -> false).status());

        var oneShort = RationalFormulaComparison.compare(left, NIL, CENTER,
            LIMITS.withMaximumWork(complete.work() - 1), () -> false);
        assertEquals(Status.INCONCLUSIVE, oneShort.status());
        assertEquals("WORK_LIMIT", oneShort.reason());
        assertTrue(oneShort.evidence().isEmpty());
        var checked = RationalFormulaComparison.verify(left, NIL, CENTER, proof, LIMITS, () -> false);
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, checked.status(), checked.reason());
        var verificationShort = RationalFormulaComparison.verify(left, NIL, CENTER, proof,
            LIMITS.withMaximumWork(checked.work() - 1), () -> false);
        assertEquals(Status.INCONCLUSIVE, verificationShort.status());
        assertEquals("WORK_LIMIT", verificationShort.reason());
        assertTrue(verificationShort.evidence().isEmpty());
        var tiny = RationalFormulaComparison.compare(left, NIL, CENTER,
            new IterationLimits(100_000_000, 2, 60_000), () -> false);
        assertEquals(Status.INCONCLUSIVE, tiny.status());
        assertTrue(tiny.evidence().isEmpty());
        var huge = RationalFormulaComparison.compare(UNIT, UNIT,
            new Assignment(Integer.MAX_VALUE, Map.of()), LIMITS, () -> false);
        assertEquals(Status.INCONCLUSIVE, huge.status());
        assertEquals("TERM_LIMIT", huge.reason());
        assertTrue(huge.evidence().isEmpty());

        var polls = new AtomicInteger();
        var counted = RationalFormulaComparison.verify(left, NIL, CENTER, proof, LIMITS,
            () -> { polls.incrementAndGet(); return false; });
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, counted.status());
        int finalPoll = polls.get();
        polls.set(0);
        var cancelled = RationalFormulaComparison.verify(left, NIL, CENTER, proof, LIMITS,
            () -> polls.incrementAndGet() >= finalPoll);
        assertEquals(Status.INCONCLUSIVE, cancelled.status());
        assertEquals("CANCELLED", cancelled.reason());
        assertTrue(cancelled.evidence().isEmpty());
    }

    private static RationalFormula difference(RationalFormula left, RationalFormula right) {
        return new Sum(left, new Product(new Scalar(NEGATIVE_ONE), right));
    }

    private static Assignment threeByThreeAssignment() {
        return new Assignment(3, Map.of(
            10, new Matrix(3, List.of(ZERO, ZERO, ZERO, ZERO, ONE, ZERO, ZERO, ZERO, integer(2))),
            -3, new Matrix(3, List.of(ZERO, ONE, ZERO, ZERO, ZERO, ONE, ONE, ZERO, ZERO))));
    }

    private static Matrix evaluate(RationalFormula source, Certificate certificate, Assignment assignment) {
        var result = RationalFormulaVerifier.evaluate(source, certificate, assignment, LIMITS, () -> false);
        assertEquals(RationalFormulaRealization.Status.DEFINED, result.status(), result.reason());
        return result.value().orElseThrow();
    }

    private static Status verify(RationalFormula left, RationalFormula right, Assignment center, Evidence evidence) {
        return RationalFormulaComparison.verify(left, right, center, evidence, LIMITS, () -> false).status();
    }
}
