package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.math.algorithms.linalg.AffineObservationEvaluation.Strategy.DIRECT;
import static de.regelsuche.math.algorithms.linalg.AffineObservationEvaluation.Strategy.REDUCED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.AffineObservationReduction.Observation;
import de.regelsuche.math.algorithms.linalg.AffineObservationReduction.Result;
import de.regelsuche.math.algorithms.linalg.AffineObservationReduction.Status;
import de.regelsuche.math.algorithms.linalg.AffineObservationReduction.Transition;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AffineObservationEvaluationTest {
    private static final Budget BUDGET = new Budget(1_000_000);
    private static final Transition DRIFT = transition("0 0 0 0",
        "1 0 1 0", "0 1 0 1", "0 0 1 0", "0 0 0 1");
    private static final Transition KICK = transition("0 0 1/3 1/3",
        "1 0 0 0", "0 1 0 0", "-1/2 1/2 1 0", "1/2 -1/2 0 1");
    private static final Observation CENTER = observation("1/2 1/2 0 0", "1/3");
    private static final ExactVector START = new ExactVector(vector("-2 3 1 -1/2"));

    @Test void indexedPlanReusesExactAffineProjectionsAndPreservesQueryOrder() {
        var transition = transition("1/3 -1/4 1/5", "1/2 1/2 0", "0 3/4 0", "0 0 7");
        var observation = observation("1/5 1/7 0", "2/3");
        var indices = indices(3, 0, 1, 2, 3, 0, 32);
        var prepared = AffineObservationEvaluation.prepareIndexed(transition, observation, indices, 2, BUDGET);
        var plan = complete(prepared);
        assertEquals(DIRECT, plan.strategy());
        for (var initial : List.of(vector("2/5 -5/3 1/3"), vector("-2 4 9"))) {
            var result = plan.valuesFor(new ExactVector(initial), remaining(prepared));
            assertEquals(indices.stream().map(index -> directIndex(transition, observation, initial, index.intValueExact()))
                .toList(), complete(result));
        }
        var shortBudget = plan.valuesFor(new ExactVector(vector("2/5 -5/3 1/3")), new Budget(1));
        assertEquals(Status.BUDGET_INCONCLUSIVE, shortBudget.status());
        assertEquals(1, shortBudget.work().consumedWorkUnits());
        assertTrue(shortBudget.value().isEmpty());
    }

    @Test void expensiveIndexScheduleSelectsAndReusesACheckedScalarReduction() {
        var transition = transition("1/2 -1/2 0", "3 -2 2", "-2 3 -2", "1 -1 2");
        var observation = observation("1 1 0", "1/2");
        var large = IntStream.range(0, 24).mapToObj(i -> BigInteger.valueOf(64L + 13L * i)).toList();
        var shortRun = AffineObservationEvaluation.prepareIndexed(transition, observation, indices(0, 1), 1, BUDGET);
        assertEquals(DIRECT, complete(shortRun).strategy());
        var reduced = AffineObservationEvaluation.prepareIndexed(transition, observation, large, 4, BUDGET);
        var direct = AffineObservationEvaluation.prepareIndexed(transition, observation, large, 1, BUDGET, false);
        assertEquals(REDUCED, complete(reduced).strategy());
        int directWork = direct.work().consumedWorkUnits();
        int reducedWork = reduced.work().consumedWorkUnits();
        for (int value = 0; value < 4; value++) {
            var initial = new ExactVector(List.of(Rational.of(value), Rational.of(2 - value), Rational.of(13)));
            var directResult = complete(direct).valuesFor(initial, new Budget(BUDGET.maxWorkUnits() - directWork));
            var reducedResult = complete(reduced).valuesFor(initial, new Budget(BUDGET.maxWorkUnits() - reducedWork));
            assertEquals(complete(directResult), complete(reducedResult));
            assertEquals(Collections.nCopies(large.size(), new Rational(BigInteger.valueOf(5), BigInteger.TWO)),
                complete(reducedResult));
            directWork += directResult.work().consumedWorkUnits();
            reducedWork += reducedResult.work().consumedWorkUnits();
        }
        assertTrue(reducedWork < directWork, () -> reduced.work() + " vs " + direct.work());
    }

    @Test void mixedEventSchedulesBeatBothAlwaysDirectAndAlwaysReduce() {
        var transitions = List.of(DRIFT, KICK);
        var observations = List.of(CENTER);
        int automatic = 0;
        int direct = 0;
        int reduce = 0;
        for (int size : new int[] {1, 1, 1, 512}) {
            var events = IntStream.range(0, size).map(i -> i % 2).boxed().toList();
            var autoPlan = AffineObservationEvaluation.prepareTrace(transitions, observations, size, BUDGET);
            var directPlan = AffineObservationEvaluation.prepareTrace(transitions, observations, 0, BUDGET);
            var reducedPlan = AffineObservationReduction.prepare(transitions, observations, BUDGET);
            assertEquals(size == 1 ? DIRECT : REDUCED, complete(autoPlan).strategy());
            var expected = directTrace(transitions, CENTER, START.values(), events);
            automatic += execute(complete(autoPlan).plan(), autoPlan, events, expected);
            direct += execute(complete(directPlan).plan(), directPlan, events, expected);
            reduce += execute(complete(reducedPlan), reducedPlan, events, expected);
        }
        assertTrue(automatic < direct, automatic + " vs direct " + direct);
        assertTrue(automatic < reduce, automatic + " vs reduced " + reduce);
        if (Boolean.getBoolean("affineEvaluation.printWork")) {
            System.out.println("trace automatic=" + automatic + " direct=" + direct + " reduced=" + reduce);
        }
    }

    @Test void skipsAProofForAnAlreadySmallCoordinateClosureAndChargesAnUnhelpfulProbe() {
        var local = observation("1 0 0 0", "0");
        var identity = transition("1 0 0 0", "1 0 0 0", "0 2 0 0", "0 0 3 0", "1 1 1 4");
        var direct = complete(AffineObservationEvaluation.prepareTrace(List.of(identity), List.of(local), 4_096, BUDGET));
        assertEquals(DIRECT, direct.strategy());
        assertEquals(1, direct.plan().reducedDimension());
        var detuned = transition("0 0 1/3 1/3",
            "1 0 0 0", "0 1 0 0", "-1/2 1/2 1 0", "1/4 -1/4 0 1");
        var transitions = List.of(DRIFT, detuned);
        var baseline = AffineObservationEvaluation.prepareTrace(transitions, List.of(CENTER), 0, BUDGET);
        var attempted = AffineObservationEvaluation.prepareTrace(transitions, List.of(CENTER), 512, BUDGET);
        assertEquals(DIRECT, complete(attempted).strategy());
        assertEquals(4, complete(attempted).plan().reducedDimension());
        assertTrue(attempted.work().consumedWorkUnits() > baseline.work().consumedWorkUnits());
        var reserved = AffineObservationEvaluation.prepareTrace(transitions, List.of(CENTER), 512, new Budget(512));
        assertEquals(DIRECT, complete(reserved).strategy());
        assertTrue(reserved.work().remainingWorkUnits() > 0);
        var justDirect = new Budget(baseline.work().consumedWorkUnits());
        var exactBudget = AffineObservationEvaluation.prepareTrace(transitions, List.of(CENTER), 512, justDirect);
        assertEquals(DIRECT, complete(exactBudget).strategy());
        assertEquals(0, exactBudget.work().remainingWorkUnits());
    }

    @Test void normalizesProjectedPowersBeforeRejectingLargeExactIntermediates() {
        var involution = transition("0 0", "0 2", "1/2 0");
        var even = BigInteger.ONE.shiftLeft(40);
        var result = AffineObservationEvaluation.prepareIndexed(involution, observation("1 0", "0"),
            List.of(even, even.add(BigInteger.ONE), BigInteger.ZERO), 2, BUDGET);
        var plan = complete(result);
        assertEquals(DIRECT, plan.strategy());
        assertEquals(vector("3 -10 3"),
            complete(plan.valuesFor(new ExactVector(vector("3 -5")), remaining(result))));
    }

    @Test void fallsBackToIndividualFractionsWhenNoBoundedCommonStartDenominatorExists() {
        var transition = transition("0 0", "0 1", "0 1");
        var prepared = AffineObservationEvaluation.prepareIndexed(transition, observation("1 0", "0"),
            indices(0, 1, 1), 1, BUDGET);
        var first = new Rational(BigInteger.ONE, BigInteger.ONE.shiftLeft(10_000));
        var second = new Rational(BigInteger.ONE, BigInteger.valueOf(3).pow(7_000));
        var initial = new ExactVector(List.of(first, second));
        assertEquals(List.of(first, second, second),
            complete(complete(prepared).valuesFor(initial, remaining(prepared))));
    }

    @Test void acceptsIndividuallyBoundedMatrixAndObservationFractions() {
        var first = new Rational(BigInteger.ONE, BigInteger.ONE.shiftLeft(10_000));
        var second = new Rational(BigInteger.ONE, BigInteger.valueOf(3).pow(7_000));
        var transition = new Transition(new ExactMatrix(List.of(List.of(Rational.ZERO, first),
            List.of(second, Rational.ZERO))), new ExactVector(vector("0 0")));
        var initial = new ExactVector(vector("0 1"));
        var matrixQueries = complete(AffineObservationEvaluation.prepareIndexed(transition,
            observation("1 0", "0"), indices(0, 1, 1), 1, BUDGET));
        assertEquals(List.of(Rational.ZERO, first, first), complete(matrixQueries.valuesFor(initial, BUDGET)));
        var separateWeights = new Observation(new ExactVector(List.of(first, second)), Rational.ZERO);
        var zeroIndex = complete(AffineObservationEvaluation.prepareIndexed(transition, separateWeights,
            indices(0), 1, BUDGET));
        assertEquals(List.of(second), complete(zeroIndex.valuesFor(initial, BUDGET)));
    }

    @Test void handlesConstantObservationsAndLimitsWithoutPublishingPartialPlans() {
        var growth = transition("0", "2");
        var constant = observation("0", "7/3");
        var constantPlan = complete(AffineObservationEvaluation.prepareIndexed(growth, constant,
            List.of(BigInteger.ONE.shiftLeft(55)), 1, BUDGET));
        assertEquals(vector("7/3"), complete(constantPlan.valuesFor(new ExactVector(vector("3")), BUDGET)));
        var exceeds = AffineObservationEvaluation.prepareIndexed(growth, observation("1", "0"), indices(20_000),
            1, BUDGET);
        assertEquals(Status.ARITHMETIC_LIMIT, exceeds.status());
        assertTrue(exceeds.value().isEmpty());
        var exhausted = AffineObservationEvaluation.prepareIndexed(growth, observation("1", "0"),
            indices(0, 1, 2), 1, new Budget(1));
        assertEquals(Status.BUDGET_INCONCLUSIVE, exhausted.status());
        assertEquals(1, exhausted.work().consumedWorkUnits());
        assertTrue(exhausted.value().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> AffineObservationEvaluation.prepareIndexed(growth,
            observation("1", "0"), indices(-1), 1, BUDGET));
    }

    private static int execute(AffineObservationReduction.VerifiedPlan plan, Result<?> preparation, List<Integer> events,
            List<List<Rational>> expected) {
        var binding = plan.bind(START, remaining(preparation));
        var result = complete(binding).trace(events, remaining(binding));
        assertEquals(expected, complete(result).observations());
        return preparation.work().consumedWorkUnits() + binding.work().consumedWorkUnits()
            + result.work().consumedWorkUnits();
    }

    private static Rational directIndex(Transition transition, Observation observation, List<Rational> start, int index) {
        var state = start;
        for (int i = 0; i < index; i++) state = advance(transition, state);
        return dot(observation.weights().values(), state, observation.offset());
    }

    private static List<List<Rational>> directTrace(List<Transition> transitions, Observation observation,
            List<Rational> start, List<Integer> events) {
        var state = start;
        var result = new ArrayList<List<Rational>>();
        for (int event : events) {
            state = advance(transitions.get(event), state);
            result.add(List.of(dot(observation.weights().values(), state, observation.offset())));
        }
        return result;
    }

    private static List<Rational> advance(Transition transition, List<Rational> state) {
        var next = new ArrayList<Rational>();
        for (int i = 0; i < state.size(); i++) {
            next.add(dot(transition.linear().rows().get(i), state, transition.offset().get(i)));
        }
        return next;
    }

    private static Rational dot(List<Rational> row, List<Rational> state, Rational offset) {
        var sum = offset;
        for (int i = 0; i < state.size(); i++) sum = sum.add(row.get(i).multiply(state.get(i)));
        return sum;
    }

    private static List<BigInteger> indices(long... values) {
        return Arrays.stream(values).mapToObj(BigInteger::valueOf).toList();
    }

    private static Transition transition(String offset, String... rows) {
        return new Transition(new ExactMatrix(Arrays.stream(rows).map(AffineObservationEvaluationTest::vector).toList()),
            new ExactVector(vector(offset)));
    }

    private static Observation observation(String weights, String offset) {
        return new Observation(new ExactVector(vector(weights)), Rational.fromExact(ExactRational.parse(offset)));
    }

    private static List<Rational> vector(String values) {
        return Arrays.stream(values.split(" ")).map(ExactRational::parse).map(Rational::fromExact).toList();
    }

    private static Budget remaining(Result<?> result) {
        return new Budget(result.work().remainingWorkUnits());
    }

    private static <T> T complete(Result<T> result) {
        assertEquals(Status.COMPLETE, result.status(), result.work().toString());
        return result.value().orElseThrow();
    }
}
