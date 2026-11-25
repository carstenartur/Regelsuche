package de.regelsuche.math.algorithms.noncommutative;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Inverse;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Product;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Scalar;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Sum;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Variable;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaComparison.Status;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static de.regelsuche.scalar.ExactRational.*;
import static org.junit.jupiter.api.Assertions.*;

class RationalFormulaCenterSearchTest {
    private static final IterationLimits LIMITS = new IterationLimits(5_000_000, 500_000, 10_000);
    private static final Variable X = new Variable(17);
    private static final Variable Y = new Variable(-2);

    @Test void skipsUndefinedCentersAndReturnsIndependentlyCheckedEvidence() {
        var formula = new Inverse(new Product(X, new Sum(X, new Scalar(NEGATIVE_ONE))));
        var policy = new RationalFormulaCenterSearch.Policy(List.of(ZERO, ONE, NEGATIVE_ONE), 10);
        var result = RationalFormulaCenterSearch.compare(formula, formula, policy, LIMITS, () -> false);
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, result.status(), result.reason());
        assertEquals(3, result.centersTried());
        var center = result.center().orElseThrow();
        assertEquals(NEGATIVE_ONE, center.variables().get(17).entries().getFirst());
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, RationalFormulaComparison.verify(formula, formula,
            center, result.evidence().orElseThrow(), LIMITS, () -> false).status());
        assertEquals(Status.INVALID_EVIDENCE, RationalFormulaComparison.verify(X, formula,
            center, result.evidence().orElseThrow(), LIMITS, () -> false).status());
    }

    @Test void preservesOriginalInverseDomainWhileComparingWithoutManualCenter() {
        var left = new Inverse(new Inverse(X));
        var result = RationalFormulaCenterSearch.compare(left, X,
            RationalFormulaCenterSearch.Policy.defaults(), LIMITS, () -> false);
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, result.status());
        assertEquals(2, result.centersTried());
        assertEquals(2, result.evidence().orElseThrow().binding().left().obligations().size());
        assertEquals(0, result.evidence().orElseThrow().binding().right().obligations().size());
    }

    @Test void findsMatrixCounterexampleEvenWhenScalarValuesAgree() {
        var left = new Product(X, Y);
        var right = new Product(Y, X);
        var result = RationalFormulaCenterSearch.compare(left, right,
            RationalFormulaCenterSearch.Policy.defaults(), LIMITS, () -> false);
        assertEquals(Status.DISTINCT, result.status(), result.reason());
        assertEquals(2, result.center().orElseThrow().variables().size());
        assertEquals(Status.DISTINCT, RationalFormulaComparison.verify(left, right,
            result.center().orElseThrow(), result.evidence().orElseThrow(), LIMITS, () -> false).status());
    }

    @Test void allScalarSingularExpressionRemainsOpenAfterWholeGrid() {
        var commutator = new Sum(new Product(X, Y),
            new Product(new Scalar(NEGATIVE_ONE), new Product(Y, X)));
        var formula = new Inverse(commutator);
        var result = RationalFormulaCenterSearch.compare(formula, formula,
            new RationalFormulaCenterSearch.Policy(List.of(ZERO, ONE), 10), LIMITS, () -> false);
        assertEquals(Status.INCONCLUSIVE, result.status());
        assertEquals("CENTER_GRID_EXHAUSTED", result.reason());
        assertEquals(4, result.centersTried());
        assertTrue(result.evidence().isEmpty());
        assertTrue(result.center().isEmpty());
    }

    @Test void candidateAndTotalWorkLimitsNeverBecomeProofs() {
        var left = new Inverse(X);
        var shortSearch = RationalFormulaCenterSearch.compare(left, left,
            new RationalFormulaCenterSearch.Policy(List.of(ZERO, ONE), 1), LIMITS, () -> false);
        assertEquals("CENTER_LIMIT", shortSearch.reason());
        assertTrue(shortSearch.evidence().isEmpty());
        var policy = new RationalFormulaCenterSearch.Policy(List.of(ZERO, ONE), 2);
        var completed = RationalFormulaCenterSearch.compare(left, left, policy, LIMITS, () -> false);
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, completed.status());
        var incomplete = RationalFormulaCenterSearch.compare(left, left, policy,
            LIMITS.withMaximumWork(completed.work() - 1), () -> false);
        assertEquals(Status.INCONCLUSIVE, incomplete.status());
        assertEquals("WORK_LIMIT", incomplete.reason());
        assertTrue(incomplete.evidence().isEmpty());
    }

    @Test void cancellationAndStorageLimitsApplyToAllAttempts() {
        var policy = RationalFormulaCenterSearch.Policy.defaults();
        var polls = new AtomicInteger();
        var completed = RationalFormulaCenterSearch.compare(X, X, policy, LIMITS,
            () -> { polls.incrementAndGet(); return false; });
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, completed.status());
        int finalPoll = polls.get();
        polls.set(0);
        var stopped = RationalFormulaCenterSearch.compare(X, X, policy, LIMITS,
            () -> polls.incrementAndGet() >= finalPoll);
        assertEquals("CANCELLED", stopped.reason());
        assertTrue(stopped.evidence().isEmpty());
        assertEquals(Status.INCONCLUSIVE, RationalFormulaCenterSearch.compare(X, X, policy,
            new IterationLimits(100_000, 2, 10_000), () -> false).status());
    }

    @Test void constantFormulasNeedOneEmptyAssignmentAndBadPoliciesAreRejected() {
        var constant = new Scalar(integer(7));
        var result = RationalFormulaCenterSearch.compare(constant, constant,
            RationalFormulaCenterSearch.Policy.defaults(), LIMITS, () -> false);
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, result.status());
        assertEquals(1, result.centersTried());
        assertTrue(result.center().orElseThrow().variables().isEmpty());
        assertThrows(IllegalArgumentException.class,
            () -> new RationalFormulaCenterSearch.Policy(List.of(), 1));
        assertThrows(IllegalArgumentException.class,
            () -> new RationalFormulaCenterSearch.Policy(List.of(ZERO), -1));
    }
}
