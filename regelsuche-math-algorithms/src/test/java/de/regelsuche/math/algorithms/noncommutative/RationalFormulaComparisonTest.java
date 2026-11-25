package de.regelsuche.math.algorithms.noncommutative;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.*;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaComparison.*;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.*;
import static de.regelsuche.scalar.ExactRational.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RationalFormulaComparisonTest {
    private static final RationalFormula X = new Variable(10), Y = new Variable(-3);
    private static final RationalFormula UNIT = new Scalar(ONE), NIL = new Scalar(ZERO);
    private static final Matrix ZERO_MATRIX = new Matrix(1, List.of(ZERO));
    private static final Assignment CENTER = new Assignment(1, Map.of(10, ZERO_MATRIX, -3, ZERO_MATRIX));
    private static final IterationLimits LIMITS = new IterationLimits(20_000_000, 500_000, 15_000);

    private static RationalFormula diff(RationalFormula a, RationalFormula b) {
        return new Sum(a, new Product(new Scalar(NEGATIVE_ONE), b));
    }

    @Test void provesPushThroughAndRejectsForgedSpanSourceOrCenter() {
        var left = new Product(new Inverse(diff(UNIT, new Product(X, Y))), X);
        var right = new Product(X, new Inverse(diff(UNIT, new Product(Y, X))));
        var result = RationalFormulaComparison.compare(left, right, CENTER, LIMITS, () -> false);
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, result.status(), result.reason());
        var proof = (Invariant) result.evidence().orElseThrow();
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, verify(left, right, CENTER, proof));
        assertEquals(Status.INVALID_EVIDENCE, verify(left, NIL, CENTER, proof));
        assertEquals(Status.INVALID_EVIDENCE, verify(left, right, CENTER, new Invariant(proof.binding(), List.of())));
        assertTrue(proof.columns().size() > 1);
        assertEquals(Status.INVALID_EVIDENCE, verify(left, right, CENTER,
            new Invariant(proof.binding(), List.of(proof.columns().getFirst()))));
        var changed = new Assignment(1, Map.of(10, new Matrix(1, List.of(ONE)), -3, ZERO_MATRIX));
        assertEquals(Status.INVALID_EVIDENCE, verify(left, right, changed, proof));
    }

    @Test void noncommutingWitnessAndUndefinedPoints() {
        var xy = new Product(X, Y);
        var yx = new Product(Y, X);
        var result = RationalFormulaComparison.compare(xy, yx, CENTER, LIMITS, () -> false);
        assertEquals(Status.DISTINCT, result.status());
        var witness = (Witness) result.evidence().orElseThrow();
        assertEquals(3, witness.assignment().order());
        assertEquals(Status.DISTINCT, verify(xy, yx, CENTER, witness));
        assertEquals(Status.INVALID_EVIDENCE, verify(xy, yx, CENTER, new Witness(witness.binding(), CENTER)));
        var one = new Assignment(1, Map.of(10, new Matrix(1, List.of(ONE)), -3, new Matrix(1, List.of(ONE))));
        var nonzeroCenter = RationalFormulaComparison.compare(xy, yx, one, LIMITS, () -> false);
        assertEquals(Status.DISTINCT, nonzeroCenter.status());
        var inverse = new Inverse(X);
        var atOne = RationalFormulaComparison.compare(inverse, NIL, one, LIMITS, () -> false);
        var binding = ((Witness) atOne.evidence().orElseThrow()).binding();
        assertEquals(Status.INVALID_EVIDENCE, verify(inverse, NIL, one, new Witness(binding, CENTER)));
        assertEquals(Status.INCONCLUSIVE, RationalFormulaComparison.compare(new Inverse(diff(xy, yx)),
            NIL, one, LIMITS, () -> false).status());
    }

    @Test void suppliedUnitriangularWitnessSeparatesTheOriginalProductsByE13() {
        var xy = new Product(X, Y);
        var yx = new Product(Y, X);
        var one = new Matrix(1, List.of(ONE));
        var center = new Assignment(1, Map.of(10, one, -3, one));
        var evidence = RationalFormulaComparison.compare(xy, yx, center, LIMITS, () -> false).evidence().orElseThrow();
        var substitution = new Assignment(3, Map.of(
            10, new Matrix(3, List.of(ONE, ONE, ZERO, ZERO, ONE, ZERO, ZERO, ZERO, ONE)),
            -3, new Matrix(3, List.of(ONE, ZERO, ZERO, ZERO, ONE, ONE, ZERO, ZERO, ONE))));
        assertEquals(Status.DISTINCT, verify(xy, yx, center, new Witness(evidence.binding(), substitution)));
        var a = RationalFormulaVerifier.evaluate(xy, evidence.binding().left(), substitution, LIMITS, () -> false)
            .value().orElseThrow();
        var b = RationalFormulaVerifier.evaluate(yx, evidence.binding().right(), substitution, LIMITS, () -> false)
            .value().orElseThrow();
        for (int i = 0; i < 9; i++) assertEquals(i == 2 ? ONE : ZERO, a.entries().get(i).subtract(b.entries().get(i)));
    }

    @Test void resourceStopsDuringCheckingAndEqualityOnOverlappingDomains() {
        var reciprocal = new Inverse(X);
        var doubleInverse = new Inverse(reciprocal);
        var center = new Assignment(1, Map.of(10, new Matrix(1, List.of(integer(2)))));
        var found = RationalFormulaComparison.compare(doubleInverse, X, center, LIMITS, () -> false);
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, found.status());
        var proof = found.evidence().orElseThrow();
        var checked = RationalFormulaComparison.verify(doubleInverse, X, center, proof, LIMITS, () -> false);
        var tooSmall = LIMITS.withMaximumWork(checked.work() - 1);
        var limited = RationalFormulaComparison.verify(doubleInverse, X, center, proof, tooSmall, () -> false);
        assertEquals(Status.INCONCLUSIVE, limited.status());
        assertTrue(limited.evidence().isEmpty());
        int[] calls = {0};
        var cancelled = RationalFormulaComparison.verify(doubleInverse, X, center, proof, LIMITS, () -> ++calls[0] > 50);
        assertEquals("CANCELLED", cancelled.reason());
        assertEquals(Status.INCONCLUSIVE, RationalFormulaComparison.compare(doubleInverse, X, center,
            new IterationLimits(1_000_000, 10, 10_000), () -> false).status());
    }

    @Test void zeroTimesUndefinedStillRequiresCenterAndLimitsNeverProveEquality() {
        var left = new Product(NIL, new Inverse(X));
        assertEquals(Status.INCONCLUSIVE, RationalFormulaComparison.compare(left, NIL, CENTER, LIMITS, () -> false).status());
        assertEquals(Status.INCONCLUSIVE, RationalFormulaComparison.compare(X, X, CENTER, LIMITS, () -> true).status());
        assertEquals(Status.INCONCLUSIVE, RationalFormulaComparison.compare(X, X, CENTER,
            LIMITS.withMaximumWork(1), () -> false).status());
    }

    private static Status verify(RationalFormula left, RationalFormula right, Assignment center, Evidence evidence) {
        return RationalFormulaComparison.verify(left, right, center, evidence, LIMITS, () -> false).status();
    }
}
