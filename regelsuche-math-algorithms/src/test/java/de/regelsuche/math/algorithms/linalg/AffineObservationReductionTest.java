package de.regelsuche.math.algorithms.linalg;

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
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AffineObservationReductionTest {
    private static final Budget ENOUGH = new Budget(100_000);
    private static final Transition DRIFT = transition("0 0 0 0",
        "1 0 1 0", "0 1 0 1", "0 0 1 0", "0 0 0 1");
    private static final Transition KICK = transition("0 0 1/3 1/3",
        "1 0 0 0", "0 1 0 0", "-1/2 1/2 1 0", "1/2 -1/2 0 1");
    private static final Observation CENTER = observation("1/2 1/2 0 0", "0");
    private static final ExactVector INITIAL = new ExactVector(vector("-2 3 1 -1/2"));

    @Test void discoversTheMissingStateAndHandlesNoncommutingEventsAndAffineOutputs() {
        var plan = complete(AffineObservationReduction.prepare(List.of(DRIFT, KICK),
            List.of(CENTER, observation("1 1 0 0", "1")), ENOUGH));
        assertEquals(4, plan.sourceDimension());
        assertEquals(2, plan.reducedDimension());
        assertEquals(List.of(new ExactVector(vector("1/2 1/2 0 0")), new ExactVector(vector("0 0 1/2 1/2"))), plan.basis());

        var bound = complete(plan.bind(INITIAL, ENOUGH));
        assertEquals(vector("1/2 2"), complete(bound.observe(ENOUGH)));
        var run = complete(bound.trace(List.of(0, 1, 0), ENOUGH));
        assertEquals(List.of(vector("3/4 5/2"), vector("3/4 5/2"), vector("4/3 11/3")), run.observations());
        assertEquals(List.of(vector("1/2 2"), vector("13/12 19/6"), vector("5/3 13/3")),
            complete(bound.trace(List.of(1, 0, 0), ENOUGH)).observations());

        var middle = complete(bound.advance(0, ENOUGH));
        assertEquals(run.observations().subList(1, 3), complete(middle.trace(List.of(1, 0), ENOUGH)).observations());
        assertEquals(run.observations().getLast(), complete(run.finalState().observe(ENOUGH)));
        assertEquals(vector("1/2 2"), complete(bound.observe(ENOUGH)));
    }

    @Test void distinguishesFullStateFromAnObservationThatNeedsNoCoordinates() {
        var detuned = transition("0 0 1/3 1/3",
            "1 0 0 0", "0 1 0 0", "-1/2 1/2 1 0", "1/4 -1/4 0 1");
        var full = complete(AffineObservationReduction.prepare(List.of(DRIFT, detuned), List.of(CENTER), ENOUGH));
        assertEquals(4, full.reducedDimension());

        var constant = complete(AffineObservationReduction.prepare(List.of(DRIFT, detuned),
            List.of(observation("0 0 0 0", "-13")), ENOUGH));
        assertEquals(0, constant.reducedDimension());
        var bound = complete(constant.bind(INITIAL, ENOUGH));
        assertEquals(List.of(vector("-13"), vector("-13")), complete(bound.trace(List.of(1, 0), ENOUGH)).observations());
        assertThrows(IllegalArgumentException.class, () -> bound.advance(2, ENOUGH));
    }

    @Test void preservesIndependentObservationsWhosePivotsAreDiscoveredOutOfCoordinateOrder() {
        var shift = transition("1 0 0 0", "1 0 0 0", "0 1 0 0", "0 0 1 0", "1 1 0 1");
        var plan = complete(AffineObservationReduction.prepare(List.of(shift),
            List.of(observation("0 0 0 1", "0"), observation("0 1 0 0", "0")), ENOUGH));
        assertEquals(3, plan.reducedDimension());
        var bound = complete(plan.bind(INITIAL, ENOUGH));
        assertEquals(List.of(vector("1/2 3"), vector("5/2 3")), complete(bound.trace(List.of(0, 0), ENOUGH)).observations());
    }

    @Test void acceptsTheEmptyEventAlphabetForAnInitialStateObservation() {
        var plan = complete(AffineObservationReduction.prepare(List.of(), List.of(CENTER), ENOUGH));
        assertEquals(1, plan.reducedDimension());
        var state = complete(plan.bind(INITIAL, ENOUGH));
        assertEquals(vector("1/2"), complete(state.observe(ENOUGH)));
        assertTrue(complete(state.trace(List.of(), ENOUGH)).observations().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> state.advance(0, ENOUGH));
    }

    @Test void updatesSeveralCoordinatesSimultaneouslyAndRebindsTheSamePlan() {
        var swap = transition("0 0", "0 1", "1 0");
        var plan = complete(AffineObservationReduction.prepare(List.of(swap), List.of(observation("1 0", "0")), ENOUGH));
        assertEquals(2, plan.reducedDimension());
        var first = complete(plan.bind(new ExactVector(vector("3 -1")), ENOUGH));
        var second = complete(plan.bind(new ExactVector(vector("7 12")), ENOUGH));
        assertEquals(List.of(vector("-1"), vector("3")), complete(first.trace(List.of(0, 0), ENOUGH)).observations());
        assertEquals(List.of(vector("12"), vector("7")), complete(second.trace(List.of(0, 0), ENOUGH)).observations());
    }

    @Test void rejectsIncompatibleMapsObservationsAndInitialVectors() {
        var projection = transition("0", "1");
        assertThrows(IllegalArgumentException.class,
            () -> AffineObservationReduction.prepare(List.of(DRIFT, projection), List.of(CENTER), ENOUGH));
        assertThrows(IllegalArgumentException.class,
            () -> AffineObservationReduction.prepare(List.of(DRIFT), List.of(), ENOUGH));
        assertThrows(IllegalArgumentException.class, () -> transition("0", "1 0"));
        var plan = complete(AffineObservationReduction.prepare(List.of(projection), List.of(observation("1", "0")), ENOUGH));
        assertThrows(IllegalArgumentException.class, () -> plan.bind(INITIAL, ENOUGH));
    }

    @Test void enforcesWorkAcrossTheWholeTraceAndLeavesTheBoundStateReusable() {
        var prepared = AffineObservationReduction.prepare(List.of(DRIFT, KICK), List.of(CENTER), ENOUGH);
        int setup = prepared.work().consumedWorkUnits();
        assertExhausted(setup - 1,
            AffineObservationReduction.prepare(List.of(DRIFT, KICK), List.of(CENTER), new Budget(setup - 1)));
        var plan = complete(prepared);
        var binding = plan.bind(INITIAL, ENOUGH);
        int bindWork = binding.work().consumedWorkUnits();
        assertExhausted(bindWork - 1, plan.bind(INITIAL, new Budget(bindWork - 1)));
        var bound = complete(binding);
        var events = List.of(0, 1, 0);
        var successful = bound.trace(events, ENOUGH);
        int units = successful.work().consumedWorkUnits();
        assertExhausted(units - 1, bound.trace(events, new Budget(units - 1)));
        assertEquals(complete(successful).observations(), complete(bound.trace(events, new Budget(units))).observations());
    }

    @Test void refusesUnboundedExactArithmeticWithoutPublishingAPartialTrace() {
        var doubleValue = transition("0", "2");
        var observation = observation("1", "0");
        var plan = complete(AffineObservationReduction.prepare(List.of(doubleValue), List.of(observation), ENOUGH));
        var large = new Rational(BigInteger.ONE.shiftLeft(16_382), BigInteger.ONE);
        var bound = complete(plan.bind(new ExactVector(List.of(large)), ENOUGH));
        assertEquals(Status.ARITHMETIC_LIMIT, bound.trace(List.of(0, 0), ENOUGH).status());
        assertTrue(bound.trace(List.of(0, 0), ENOUGH).value().isEmpty());
        assertEquals(List.of(large), complete(bound.observe(ENOUGH)));
    }

    private static void assertExhausted(int units, Result<?> result) {
        assertEquals(Status.BUDGET_INCONCLUSIVE, result.status());
        assertEquals(units, result.work().consumedWorkUnits());
        assertTrue(result.value().isEmpty());
    }

    private static <T> T complete(Result<T> result) {
        assertEquals(Status.COMPLETE, result.status());
        return result.value().orElseThrow();
    }

    private static Observation observation(String weights, String offset) {
        return new Observation(new ExactVector(vector(weights)), rational(offset));
    }

    private static Transition transition(String offset, String... rows) {
        return new Transition(new ExactMatrix(Arrays.stream(rows).map(AffineObservationReductionTest::vector).toList()),
            new ExactVector(vector(offset)));
    }

    private static List<Rational> vector(String values) {
        return Arrays.stream(values.split(" ")).map(AffineObservationReductionTest::rational).toList();
    }

    private static Rational rational(String value) {
        return Rational.fromExact(ExactRational.parse(value));
    }
}
