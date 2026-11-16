package de.regelsuche.math.algorithms.linalg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.math.algorithms.linalg.RecurrenceObservationKrylov.Result;
import de.regelsuche.math.algorithms.linalg.RecurrenceObservationKrylov.Status;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RecurrenceObservationKrylovTest {
    private static final Budget BUDGET = new Budget(200_000);
    private static final BigInteger HUGE = BigInteger.TEN.pow(100);
    private static final Polynomial X = Polynomial.variable("x0");
    private static final Polynomial Y = Polynomial.variable("x1");
    private static final Polynomial Z = Polynomial.variable("x2");

    @Test void changingThreeCoordinateObservationHasUniversalOrderOne() {
        // The skew-coupled (x0, x1) block rotates, x2 changes sign. P(Tx) = -P(x).
        var transition = transition(new long[][] {{0, -1, 0}, {1, 0, 0}, {0, 0, -1}}, 0, 0, 0);
        var observation = X.pow(2).subtract(Y.pow(2)).add(Z);
        var plan = complete(RecurrenceObservationKrylov.prepare(transition, observation, BUDGET));
        assertEquals(1, plan.order());
        assertEquals(values(-1), plan.coefficients());
        var bound = complete(plan.bind(values(2, 3, 7), BUDGET));
        assertEquals(values(2, -2, 2, -2), complete(bound.valuesAt(
            List.of(BigInteger.ZERO, BigInteger.ONE, HUGE, HUGE.add(BigInteger.ONE)), BUDGET)));
        assertEquals(Rational.of(-2), complete(bound.valueAt(HUGE.add(BigInteger.ONE), new Budget(1))));

        var zeroTrajectory = complete(plan.bind(values(1, 1, 0), BUDGET));
        assertEquals(Rational.ZERO, complete(zeroTrajectory.valueAt(HUGE, BUDGET)));
        assertEquals(1, plan.order());
    }

    @Test void mixedDegreeAffineObservationHasOrderThree() {
        // P(x)=x²+x+1, T(x)=x+1, so P(T^n(2))=n²+5n+7.
        var transition = transition(new long[][] {{1}}, 1);
        var observation = X.pow(2).add(X).add(Polynomial.constant(Rational.ONE));
        var plan = complete(RecurrenceObservationKrylov.prepare(transition, observation, BUDGET));
        assertEquals(3, plan.order());
        assertEquals(values(1, -3, 3), plan.coefficients());
        var bound = complete(plan.bind(values(2), BUDGET));
        var largeValue = HUGE.pow(2).add(HUGE.multiply(BigInteger.valueOf(5))).add(BigInteger.valueOf(7));
        assertEquals(new Rational(largeValue, BigInteger.ONE), complete(bound.valueAt(HUGE, BUDGET)));
        assertEquals(values(7, 13, 21, 57), complete(bound.valuesAt(
            List.of(BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(5)), BUDGET)));
    }

    @Test void singularRationalTransitionRetainsItsFiniteTransient() {
        // T(x,y)=(y/2,0). The observation vanishes universally at step two.
        var half = new Rational(BigInteger.ONE, BigInteger.TWO);
        var transition = new Transition(new ExactMatrix(List.of(List.of(Rational.ZERO, half), values(0, 0))),
            new ExactVector(values(0, 0)));
        var plan = complete(RecurrenceObservationKrylov.prepare(transition, X, BUDGET));
        assertEquals(2, plan.order());
        assertEquals(values(0, 0), plan.coefficients());
        var start = values(2, 3);
        var indices = List.of(BigInteger.ZERO, BigInteger.ONE, HUGE);
        var expected = List.of(Rational.of(2), new Rational(BigInteger.valueOf(3), BigInteger.TWO), Rational.ZERO);
        assertEquals(expected, complete(complete(plan.bind(start, BUDGET)).valuesAt(indices, BUDGET)));
        assertEquals(expected, complete(complete(plan.prepareQueries(indices, BUDGET)).valuesFor(start, BUDGET)));
    }

    @Test void zeroAndNonzeroConstantHaveTheirOwnMinimalOrders() {
        var transition = transition(new long[][] {{0}}, 4);
        var zero = complete(RecurrenceObservationKrylov.prepare(transition, Polynomial.zero(), BUDGET));
        var constant = complete(RecurrenceObservationKrylov.prepare(
            transition, Polynomial.constant(Rational.of(7)), BUDGET));
        assertEquals(0, zero.order());
        assertEquals(List.of(), zero.coefficients());
        assertEquals(1, constant.order());
        assertEquals(values(1), constant.coefficients());
        assertEquals(Rational.ZERO, complete(complete(zero.bind(values(2), BUDGET)).valueAt(HUGE, BUDGET)));
        assertEquals(Rational.of(7), complete(complete(constant.bind(values(2), BUDGET)).valueAt(HUGE, BUDGET)));
    }

    @Test void allTenMonomialsCanContributeDistinctModes() {
        var transition = transition(new long[][] {{2, 0, 0}, {0, 3, 0}, {0, 0, 5}}, 0, 0, 0);
        var plan = complete(RecurrenceObservationKrylov.prepare(transition, allMonomials(), BUDGET));
        assertEquals(10, plan.order());
        var start = values(1, 1, 1);
        var index = BigInteger.valueOf(21);
        var bound = complete(plan.bind(start, BUDGET));
        var known = Arrays.stream(new long[] {1, 2, 3, 5, 4, 6, 10, 9, 15, 25})
            .mapToObj(value -> BigInteger.valueOf(value).pow(21)).reduce(BigInteger.ZERO, BigInteger::add);
        var expected = new Rational(known, BigInteger.ONE);
        assertEquals(expected, complete(bound.valueAt(index, BUDGET)));
        var queries = complete(plan.prepareQueries(List.of(index), BUDGET));
        assertEquals(List.of(expected), complete(queries.valuesFor(start, BUDGET)));
    }

    @Test void trajectoryProofRejectsFalseRelationsThatFitTheLinearSamples() {
        // For the proposed a(n+1)=0, x²−x vanishes at 0 and 1 but misses 2.
        var signChange = transition(new long[][] {{-1}}, 0);
        assertFalse(RecurrenceObservationProof.verify(signChange, X.pow(2).add(X), 2, values(0),
            new RecurrenceInvariantSupport.Work(BUDGET.maxWorkUnits())));

        // The same false recurrence for xy vanishes along both axes but misses (1,1).
        var identity = transition(new long[][] {{1, 0}, {0, 1}}, 0, 0);
        assertFalse(RecurrenceObservationProof.verify(identity, X.multiply(Y), 2, values(0),
            new RecurrenceInvariantSupport.Work(BUDGET.maxWorkUnits())));
    }

    @Test void trajectoryProofRejectsAFalseCachedOrbitFormThatFitsBothAxes() {
        var transition = transition(new long[][] {{1, 0}, {0, 1}}, 1, 0);
        var observation = X.pow(2).add(Y);
        var second = X.add(Polynomial.constant(Rational.of(2))).pow(2).add(Y);
        assertTrue(RecurrenceObservationProof.verifyForms(transition, observation,
            List.of(new RecurrenceObservationProof.OrbitForm(2, second)),
            new RecurrenceInvariantSupport.Work(BUDGET.maxWorkUnits())));
        assertFalse(RecurrenceObservationProof.verifyForms(transition, observation,
            List.of(new RecurrenceObservationProof.OrbitForm(2, second.add(X.multiply(Y)))),
            new RecurrenceInvariantSupport.Work(BUDGET.maxWorkUnits())));
    }

    @Test void eachPhaseStopsAtItsExactBudgetBoundary() {
        var transition = transition(new long[][] {{0, 1}, {1, 1}}, 0, 0);
        var prepared = RecurrenceObservationKrylov.prepare(transition, X, BUDGET);
        var plan = complete(prepared);
        var bound = plan.bind(values(0, 1), BUDGET);
        var query = complete(bound).valueAt(BigInteger.TEN, BUDGET);
        assertEquals(Rational.of(55), complete(query));

        assertExhausted(RecurrenceObservationKrylov.prepare(transition, X, before(prepared)));
        assertExhausted(plan.bind(values(0, 1), before(bound)));
        assertExhausted(complete(bound).valueAt(BigInteger.TEN, before(query)));
        assertEquals(Status.COMPLETE, RecurrenceObservationKrylov.prepare(transition, X, exact(prepared)).status());
        assertEquals(Status.COMPLETE, plan.bind(values(0, 1), exact(bound)).status());
        assertEquals(Status.COMPLETE, complete(bound).valueAt(BigInteger.TEN, exact(query)).status());
    }

    @Test void validatesObservationDomainAndQueryArguments() {
        var transition = transition(new long[][] {{1}}, 0);
        var oversized = Polynomial.constant(new Rational(BigInteger.ONE.shiftLeft(128), BigInteger.ONE));
        assertThrows(IllegalArgumentException.class, () -> new Budget(-1));
        assertThrows(IllegalArgumentException.class, () -> RecurrenceObservationKrylov.prepare(transition, Y, BUDGET));
        assertThrows(IllegalArgumentException.class, () -> RecurrenceObservationKrylov.prepare(transition, X.pow(3), BUDGET));
        assertThrows(IllegalArgumentException.class, () -> RecurrenceObservationKrylov.prepare(
            transition, Polynomial.term(new Monomial(Map.of("x0", Integer.MAX_VALUE)), Rational.ONE), BUDGET));
        assertThrows(IllegalArgumentException.class, () -> RecurrenceObservationKrylov.prepare(
            transition, oversized, BUDGET));
        var plan = complete(RecurrenceObservationKrylov.prepare(transition, X, BUDGET));
        assertThrows(IllegalArgumentException.class, () -> plan.bind(values(1, 2), BUDGET));
        assertThrows(IllegalArgumentException.class, () -> plan.bind(
            List.of(new Rational(BigInteger.ONE, BigInteger.ONE.shiftLeft(128))), BUDGET));
        var bound = complete(plan.bind(values(1), BUDGET));
        assertThrows(IllegalArgumentException.class, () -> bound.valueAt(BigInteger.valueOf(-1), BUDGET));
        assertThrows(UnsupportedOperationException.class, () -> plan.coefficients().set(0, Rational.ZERO));
    }

    @Test void explosiveExactQueryReachesArithmeticLimitWithoutPublishingPartialResults() {
        var transition = transition(new long[][] {{2}}, 0);
        var plan = complete(RecurrenceObservationKrylov.prepare(transition, X, BUDGET));
        var bound = complete(plan.bind(values(1), BUDGET));
        assertEquals(Rational.of(1024), complete(bound.valueAt(BigInteger.TEN, BUDGET)));
        var excessive = bound.valuesAt(List.of(BigInteger.TEN, HUGE), BUDGET);
        assertEquals(Status.ARITHMETIC_LIMIT, excessive.status());
        assertTrue(excessive.value().isEmpty());
        assertTrue(excessive.work().remainingWorkUnits() > 0);
    }

    @Test void preparedQueriesRetainIndexOrderForIndependentRationalStarts() {
        var transition = transition(new long[][] {{1}}, 1);
        var observation = X.pow(2).add(X).add(Polynomial.constant(Rational.ONE));
        var plan = complete(RecurrenceObservationKrylov.prepare(transition, observation, BUDGET));
        var indices = List.of(HUGE, BigInteger.ONE, BigInteger.ZERO, BigInteger.TWO, HUGE, BigInteger.valueOf(5));
        var mutableIndices = new ArrayList<>(indices);
        var queries = complete(plan.prepareQueries(mutableIndices, BUDGET));
        mutableIndices.clear();

        var half = new Rational(BigInteger.ONE, BigInteger.TWO);
        for (var start : List.of(Rational.of(2), Rational.of(-3), half)) {
            var expected = indices.stream().map(index -> {
                var x = start.add(new Rational(index, BigInteger.ONE));
                return x.multiply(x).add(x).add(Rational.ONE);
            }).toList();
            var answers = complete(queries.valuesFor(List.of(start), BUDGET));
            assertEquals(expected, answers);
            assertThrows(UnsupportedOperationException.class, () -> answers.set(0, Rational.ZERO));
        }
    }

    @Test void indexPreparationAmortizesAcrossStartsAndDoesNotRepeatDuplicateExponentiation() {
        var transition = transition(new long[][] {{0, 1}, {1, 1}}, 0, 0);
        var verified = RecurrenceObservationKrylov.prepare(transition, X, BUDGET);
        var plan = complete(verified);
        var setupBudget = new Budget(verified.work().remainingWorkUnits());
        var first = BigInteger.valueOf(2000);
        var second = BigInteger.valueOf(1729);
        var unique = plan.prepareQueries(List.of(first, second), setupBudget);
        var indices = List.of(first, second, first, second, first);
        var prepared = plan.prepareQueries(indices, setupBudget);
        var queries = complete(prepared);
        assertTrue(prepared.work().consumedWorkUnits() < unique.work().consumedWorkUnits() + 50);

        int repeatedWork = verified.work().consumedWorkUnits();
        int preparedWork = repeatedWork + prepared.work().consumedWorkUnits();
        for (var state : List.of(values(0, 1), values(2, 3), values(5, 8), values(13, 21))) {
            var bound = plan.bind(state, new Budget(BUDGET.maxWorkUnits() - repeatedWork));
            var oldAnswers = complete(bound).valuesAt(indices, new Budget(bound.work().remainingWorkUnits()));
            var newAnswers = queries.valuesFor(state, new Budget(BUDGET.maxWorkUnits() - preparedWork));
            assertEquals(complete(oldAnswers), complete(newAnswers));
            repeatedWork += bound.work().consumedWorkUnits() + oldAnswers.work().consumedWorkUnits();
            preparedWork += newAnswers.work().consumedWorkUnits();
        }
        assertTrue(preparedWork < repeatedWork, preparedWork + " work units vs " + repeatedWork);
        if (Boolean.getBoolean("affineEvaluation.printWork")) {
            System.out.println("indexed prepared=" + preparedWork + " rebound=" + repeatedWork);
        }
    }

    @Test void preparedQueriesRespectBudgetsAndNeverPublishPartialValues() {
        var plan = complete(RecurrenceObservationKrylov.prepare(transition(new long[][] {{2}}, 0), X, BUDGET));
        var indices = List.of(BigInteger.ZERO, BigInteger.valueOf(16_383));
        var prepared = plan.prepareQueries(indices, BUDGET);
        var queries = complete(prepared);
        var answers = queries.valuesFor(values(1), BUDGET);
        assertEquals(List.of(Rational.ONE, new Rational(BigInteger.ONE.shiftLeft(16_383), BigInteger.ONE)),
            complete(answers));

        assertExhausted(plan.prepareQueries(indices, before(prepared)));
        assertExhausted(queries.valuesFor(values(1), before(answers)));
        assertEquals(Status.COMPLETE, plan.prepareQueries(indices, exact(prepared)).status());
        assertEquals(Status.COMPLETE, queries.valuesFor(values(1), exact(answers)).status());
        assertArithmeticLimited(plan.prepareQueries(List.of(BigInteger.TEN, HUGE), BUDGET));
        assertArithmeticLimited(queries.valuesFor(values(2), BUDGET));
        assertEquals(complete(answers), complete(queries.valuesFor(values(1), BUDGET)));
    }

    @Test void preparedQueriesHandleAnEmptyBatchAndBothKindsOfZero() {
        var transition = transition(new long[][] {{-1}}, 0);
        var zero = complete(RecurrenceObservationKrylov.prepare(transition, Polynomial.zero(), BUDGET));
        var sign = complete(RecurrenceObservationKrylov.prepare(transition, X, BUDGET));
        var indices = List.of(HUGE.add(BigInteger.ONE), BigInteger.ZERO, HUGE, BigInteger.ONE);
        var zeroQueries = complete(zero.prepareQueries(indices, BUDGET));
        assertEquals(values(0, 0, 0, 0), complete(zeroQueries.valuesFor(values(1), BUDGET)));

        var queries = complete(sign.prepareQueries(indices, BUDGET));
        assertEquals(values(0, 0, 0, 0), complete(queries.valuesFor(values(0), BUDGET)));
        assertEquals(values(-3, 3, 3, -3), complete(queries.valuesFor(values(3), BUDGET)));
        var empty = complete(sign.prepareQueries(List.of(), BUDGET));
        assertEquals(List.of(), complete(empty.valuesFor(values(3), BUDGET)));
        assertThrows(IllegalArgumentException.class, () -> empty.valuesFor(values(3, 4), BUDGET));
        assertThrows(IllegalArgumentException.class, () -> sign.prepareQueries(
            List.of(BigInteger.ZERO, BigInteger.valueOf(-1)), BUDGET));
    }

    @Test void preparedQueriesAcceptAnyStartWithinTheirExactArithmeticLimit() {
        var plan = complete(RecurrenceObservationKrylov.prepare(transition(new long[][] {{-1}}, 0), X, BUDGET));
        var queries = complete(plan.prepareQueries(List.of(BigInteger.ZERO, BigInteger.ONE), BUDGET));
        var largeStart = List.of(new Rational(BigInteger.ONE.shiftLeft(128), BigInteger.ONE));
        assertEquals(List.of(largeStart.getFirst(), largeStart.getFirst().negate()),
            complete(queries.valuesFor(largeStart, BUDGET)));
        assertThrows(IllegalArgumentException.class, () -> plan.bind(largeStart, BUDGET));

        var oversized = BigInteger.ONE.shiftLeft(16_384);
        assertArithmeticLimited(queries.valuesFor(List.of(new Rational(oversized, BigInteger.ONE)), BUDGET));
        assertArithmeticLimited(queries.valuesFor(List.of(new Rational(BigInteger.ONE, oversized)), BUDGET));
    }

    @Test void preparedQueriesSkipOrbitFormsWithZeroWeightAtEveryRequestedIndex() {
        var transition = transition(new long[][] {{0, 0}, {0, 0}}, 0, 0);
        var plan = complete(RecurrenceObservationKrylov.prepare(transition, X.add(Y), BUDGET));
        var indices = IntStream.rangeClosed(1, 24).mapToObj(BigInteger::valueOf).toList();
        var startCoordinate = new Rational(BigInteger.ONE.shiftLeft(16_383), BigInteger.ONE);
        var start = List.of(startCoordinate, startCoordinate);

        var future = complete(plan.prepareQueries(indices, BUDGET));
        assertEquals(values(new long[24]), complete(future.valuesFor(start, BUDGET)));
        var includingOrigin = complete(plan.prepareQueries(List.of(BigInteger.ONE, BigInteger.ZERO), BUDGET));
        assertArithmeticLimited(includingOrigin.valuesFor(start, BUDGET));

        var nilpotent = transition(new long[][] {{0, 1}, {0, 0}}, 0, 0);
        var orderTwo = complete(RecurrenceObservationKrylov.prepare(nilpotent, X.add(Y), BUDGET));
        var onlySecondForm = complete(orderTwo.prepareQueries(List.of(BigInteger.ONE, BigInteger.TWO), BUDGET));
        assertEquals(List.of(startCoordinate, Rational.ZERO), complete(onlySecondForm.valuesFor(start, BUDGET)));
    }

    @Test void denseCoefficientFormationRespectsTheRrefSizeGuard() {
        var powerOfTwo = BigInteger.ONE.shiftLeft(127);
        var diagonal = List.of(1L, 3L, 5L).stream()
            .map(i -> new Rational(powerOfTwo.subtract(BigInteger.valueOf(i)), BigInteger.ONE)).toList();
        var transition = new Transition(new ExactMatrix(List.of(
            List.of(diagonal.get(0), Rational.ZERO, Rational.ZERO),
            List.of(Rational.ZERO, diagonal.get(1), Rational.ZERO),
            List.of(Rational.ZERO, Rational.ZERO, diagonal.get(2)))), new ExactVector(values(0, 0, 0)));
        var result = RecurrenceObservationKrylov.prepare(transition, allMonomials(), BUDGET);
        assertEquals(Status.ARITHMETIC_LIMIT, result.status());
        assertTrue(result.value().isEmpty());
    }

    private static <T> T complete(Result<T> result) {
        assertEquals(Status.COMPLETE, result.status());
        return result.value().orElseThrow();
    }

    private static Budget before(Result<?> result) {
        return new Budget(result.work().consumedWorkUnits() - 1);
    }

    private static Budget exact(Result<?> result) {
        return new Budget(result.work().consumedWorkUnits());
    }

    private static void assertExhausted(Result<?> result) {
        assertEquals(Status.BUDGET_INCONCLUSIVE, result.status());
        assertFalse(result.value().isPresent());
        assertEquals(0, result.work().remainingWorkUnits());
    }

    private static void assertArithmeticLimited(Result<?> result) {
        assertEquals(Status.ARITHMETIC_LIMIT, result.status());
        assertTrue(result.value().isEmpty());
        assertTrue(result.work().remainingWorkUnits() > 0);
    }

    private static List<Rational> values(long... entries) {
        return Arrays.stream(entries).mapToObj(Rational::of).toList();
    }

    private static Polynomial allMonomials() {
        return Polynomial.constant(Rational.ONE).add(X).add(Y).add(Z)
            .add(X.pow(2)).add(X.multiply(Y)).add(X.multiply(Z)).add(Y.pow(2)).add(Y.multiply(Z)).add(Z.pow(2));
    }

    private static Transition transition(long[][] matrix, long... offset) {
        return new Transition(new ExactMatrix(Arrays.stream(matrix).map(RecurrenceObservationKrylovTest::values).toList()),
            new ExactVector(values(offset)));
    }
}
