package de.regelsuche.math.algorithms.noncommutative;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.*;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaCenterSearch.MatrixPolicy;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaComparison.Status;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

import static de.regelsuche.scalar.ExactRational.*;
import static org.junit.jupiter.api.Assertions.*;

class RationalFormulaMatrixCenterSearchTest {
    private static final Variable X = new Variable(17), Y = new Variable(-2);
    private static final Scalar UNIT = new Scalar(ONE), NIL = new Scalar(ZERO);
    private static final RationalFormula COMMUTATOR = new Sum(new Product(X, Y),
        new Product(new Scalar(NEGATIVE_ONE), new Product(Y, X)));
    private static final RationalFormula IDENTITY = new Product(new Inverse(COMMUTATOR), COMMUTATOR);
    private static final IterationLimits LIMITS = new IterationLimits(100_000_000, 1_000_000, 60_000);

    @Test void genericMatrixEntryGridFindsACenterThatEveryScalarGridMisses(TestReporter reporter) {
        var policy = new MatrixPolicy(List.of(ZERO, ONE), List.of(1, 2), 40);
        var result = RationalFormulaCenterSearch.compare(IDENTITY, UNIT, policy, LIMITS, () -> false);
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, result.status(), result.reason());
        assertEquals(2, result.center().orElseThrow().order());
        assertTrue(result.centersTried() > 4);
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, RationalFormulaComparison.verify(IDENTITY, UNIT,
            result.center().orElseThrow(), result.evidence().orElseThrow(), LIMITS, () -> false).status());
        var old = RationalFormulaCenterSearch.compare(IDENTITY, UNIT,
            new RationalFormulaCenterSearch.Policy(List.of(ZERO, ONE), 40), LIMITS, () -> false);
        assertEquals(Status.INCONCLUSIVE, old.status());
        assertEquals("CENTER_GRID_EXHAUSTED", old.reason());
        assertEquals(4, old.centersTried());
        reporter.publishEntry("binary-grid", "centers=" + result.centersTried() + ";work=" + result.work());
    }

    @Test void defaultMatrixPolicyCompletesUnderTheSharedCellCeiling(TestReporter reporter) {
        var result = RationalFormulaCenterSearch.compare(IDENTITY, UNIT, MatrixPolicy.defaults(), LIMITS, () -> false);
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, result.status(), result.reason());
        assertEquals(2, result.center().orElseThrow().order());
        assertTrue(result.centersTried() > 1);
        assertTrue(result.work() > 0);
        reporter.publishEntry("default-grid", "centers=" + result.centersTried() + ";work=" + result.work());
    }

    @Test void finiteGridAndCandidateLimitsNeverCertifyAnEmptyOriginalDomain() {
        var inverseZero = new Inverse(NIL);
        var capped = RationalFormulaCenterSearch.compare(new Product(inverseZero, X), NIL,
            new MatrixPolicy(List.of(ZERO, ONE), List.of(2), 3), LIMITS, () -> false);
        assertEquals(Status.INCONCLUSIVE, capped.status());
        assertEquals("CENTER_LIMIT", capped.reason());
        assertEquals(3, capped.centersTried());
        assertTrue(capped.evidence().isEmpty());
        var exhausted = RationalFormulaCenterSearch.compare(new Inverse(X), X,
            new MatrixPolicy(List.of(ZERO), List.of(2), 10), LIMITS, () -> false);
        assertEquals(Status.INCONCLUSIVE, exhausted.status());
        assertEquals("CENTER_GRID_EXHAUSTED", exhausted.reason());
        assertEquals(1, exhausted.centersTried());
        assertTrue(exhausted.center().isEmpty());
    }

    @Test void centerLimitPrecedesAllocationForTheNextMatrixOrder() {
        var result = RationalFormulaCenterSearch.compare(new Inverse(X), X,
            new MatrixPolicy(List.of(ZERO), List.of(1, Integer.MAX_VALUE), 1), LIMITS, () -> false);
        assertEquals(Status.INCONCLUSIVE, result.status());
        assertEquals("CENTER_LIMIT", result.reason());
        assertEquals(1, result.centersTried());
        assertTrue(result.center().isEmpty());
        assertTrue(result.evidence().isEmpty());
    }

    @Test void failedCentersAndFinalVerificationShareOneWorkBudget() {
        var policy = new MatrixPolicy(List.of(ZERO, ONE), List.of(1, 2), 40);
        var complete = RationalFormulaCenterSearch.compare(IDENTITY, UNIT, policy, LIMITS, () -> false);
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, complete.status(), complete.reason());
        var stopped = RationalFormulaCenterSearch.compare(IDENTITY, UNIT, policy,
            LIMITS.withMaximumWork(complete.work() - 1), () -> false);
        assertEquals(Status.INCONCLUSIVE, stopped.status());
        assertEquals("WORK_LIMIT", stopped.reason());
        assertTrue(stopped.evidence().isEmpty());
        assertTrue(stopped.center().isEmpty());
        assertTrue(stopped.centersTried() > 1);
    }

    @Test void cancellationAndMatrixDimensionsCannotEscapeTheSearchBudget() {
        var policy = new MatrixPolicy(List.of(ZERO), List.of(2), 1);
        var polls = new AtomicInteger();
        var complete = RationalFormulaCenterSearch.compare(X, X, policy, LIMITS,
            () -> { polls.incrementAndGet(); return false; });
        assertEquals(Status.EQUAL_ON_SHARED_DOMAIN, complete.status());
        int finalPoll = polls.get();
        polls.set(0);
        var cancelled = RationalFormulaCenterSearch.compare(X, X, policy, LIMITS,
            () -> polls.incrementAndGet() >= finalPoll);
        assertEquals(Status.INCONCLUSIVE, cancelled.status());
        assertEquals("CANCELLED", cancelled.reason());
        assertTrue(cancelled.evidence().isEmpty());
        var huge = RationalFormulaCenterSearch.compare(X, X,
            new MatrixPolicy(List.of(ZERO), List.of(Integer.MAX_VALUE), 1), LIMITS, () -> false);
        assertEquals(Status.INCONCLUSIVE, huge.status());
        assertEquals("TERM_LIMIT", huge.reason());
        assertEquals(0, huge.centersTried());
        var tiny = RationalFormulaCenterSearch.compare(X, X, policy,
            new IterationLimits(100_000_000, 2, 60_000), () -> false);
        assertEquals(Status.INCONCLUSIVE, tiny.status());
        assertTrue(tiny.evidence().isEmpty());
    }

    @Test void constantFormulasUseOneEmptyAssignmentOfTheRequestedOrder() {
        var result = RationalFormulaCenterSearch.compare(UNIT, new Scalar(integer(2)),
            new MatrixPolicy(List.of(ZERO), List.of(2), 1), LIMITS, () -> false);
        assertEquals(Status.DISTINCT, result.status(), result.reason());
        assertEquals(1, result.centersTried());
        assertEquals(2, result.center().orElseThrow().order());
        assertTrue(result.center().orElseThrow().variables().isEmpty());
    }

    @Test void invalidPoliciesAreRejectedAndZeroAttemptsStayInconclusive() {
        assertThrows(IllegalArgumentException.class, () -> new MatrixPolicy(List.of(), List.of(2), 1));
        assertThrows(IllegalArgumentException.class, () -> new MatrixPolicy(List.of(ZERO), List.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> new MatrixPolicy(List.of(ZERO), List.of(0), 1));
        assertThrows(IllegalArgumentException.class, () -> new MatrixPolicy(List.of(ZERO), List.of(2, 2), 1));
        assertThrows(IllegalArgumentException.class, () -> new MatrixPolicy(List.of(ZERO), List.of(2), -1));
        var noAttempts = RationalFormulaCenterSearch.compare(X, X,
            new MatrixPolicy(List.of(ZERO), List.of(2), 0), LIMITS, () -> false);
        assertEquals(Status.INCONCLUSIVE, noAttempts.status());
        assertEquals("CENTER_LIMIT", noAttempts.reason());
        assertEquals(0, noAttempts.centersTried());
    }
}
