package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.math.algorithms.linalg.AffineObservationReduction.checkCoefficients;
import static de.regelsuche.math.algorithms.linalg.AffineObservationReduction.checkInputs;
import static de.regelsuche.math.algorithms.linalg.AffineObservationReduction.measured;
import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.addProduct;
import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.checked;
import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.multiply;

import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.AffineObservationReduction.Observation;
import de.regelsuche.math.algorithms.linalg.AffineObservationReduction.Result;
import de.regelsuche.math.algorithms.linalg.AffineObservationReduction.Transition;
import de.regelsuche.math.algorithms.linalg.AffineObservationReduction.VerifiedPlan;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Exhausted;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Work;
import de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.ArithmeticLimit;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Selects and prepares an exact computation for the supplied observation workload. */
public final class AffineObservationEvaluation {
    // The preparation hint changes strategy only. Both routes return the same exact values.
    private static final int INDEXED_REDUCTION_SETUP_ESTIMATE = 384;
    private static final int PROBE_BUDGET_DIVISOR = 8;

    private AffineObservationEvaluation() { }

    public enum Strategy { DIRECT, REDUCED }

    /**
     * Prepares an immutable batch for a fixed transition. Query indices may repeat and
     * appear in any order. Expected starts helps decide whether to try a scalar
     * recurrence. Bind and evaluate each start with the remaining budget when sharing
     * one budget over all phases.
     */
    public static Result<IndexedPlan> prepareIndexed(Transition transition, Observation observation,
            List<BigInteger> indices, int expectedStarts, Budget budget) {
        return prepareIndexed(transition, observation, indices, expectedStarts, budget, true);
    }

    static Result<IndexedPlan> prepareIndexed(Transition transition, Observation observation,
            List<BigInteger> indices, int expectedStarts, Budget budget, boolean allowReduction) {
        Objects.requireNonNull(indices, "indices");
        if (expectedStarts < 0) throw new IllegalArgumentException("expected starts must be nonnegative");
        var transitions = List.of(transition);
        var observations = List.of(observation);
        int dimension = transition.linear().columns();
        return measured(budget, work -> {
            work.add(1L + indices.size());
            var requested = List.copyOf(indices);
            for (BigInteger index : requested) {
                if (index.signum() < 0) throw new IllegalArgumentException("index must be nonnegative");
            }
            checkInputs(transitions, observations, dimension, work);
            var dependencies = coordinates(transitions, observations, dimension, work);
            if (allowReduction && dimension <= 3 && dependencies.size() > 1) {
                var batch = tryIndexedReduction(transition, observation, requested, dependencies.size(), expectedStarts, work);
                if (batch != null) {
                    return new IndexedPlan(dimension, Strategy.REDUCED, (initial, remaining) ->
                        completedBatch(batch.valuesFor(initial.values(), new Budget(remaining.remaining())), remaining));
                }
            }
            var direct = DirectIndexedObservation.prepare(transition, observation, requested, dependencies, work);
            return new IndexedPlan(dimension, Strategy.DIRECT, direct::valuesFor);
        });
    }

    /** Prepared queries are immutable and can be executed for concurrent, independent starts. */
    public static final class IndexedPlan {
        private final int dimension;
        private final Strategy strategy;
        private final IndexEvaluation evaluation;

        private IndexedPlan(int dimension, Strategy strategy, IndexEvaluation evaluation) {
            this.dimension = dimension;
            this.strategy = strategy;
            this.evaluation = evaluation;
        }

        public Strategy strategy() {
            return strategy;
        }

        public Result<List<Rational>> valuesFor(ExactVector initialState, Budget budget) {
            Objects.requireNonNull(initialState, "initialState");
            if (initialState.dimension() != dimension) throw new IllegalArgumentException("initial state dimension mismatch");
            return measured(budget, work -> {
                checkCoefficients(initialState.values(), work);
                return evaluation.evaluate(initialState, work);
            });
        }
    }

    @FunctionalInterface
    private interface IndexEvaluation {
        List<Rational> evaluate(ExactVector initialState, Work work);
    }

    public record TracePlan(Strategy strategy, VerifiedPlan plan) { }

    /**
     * Prepares a reusable event plan. Expected events is a workload hint. The resulting
     * plan accepts any valid sequence of labelled transitions, and exposes the same
     * bind, advance, observe and trace methods for either strategy.
     */
    public static Result<TracePlan> prepareTrace(List<Transition> transitions, List<Observation> observations,
            int expectedEvents, Budget budget) {
        Objects.requireNonNull(transitions, "transitions");
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) throw new IllegalArgumentException("at least one observation is required");
        if (expectedEvents < 0) throw new IllegalArgumentException("expected events must be nonnegative");
        return measured(budget, work -> {
            work.add(1L + transitions.size() + observations.size());
            var maps = List.copyOf(transitions);
            var outputs = List.copyOf(observations);
            int dimension = outputs.getFirst().weights().dimension();
            checkInputs(maps, outputs, dimension, work);
            var dependencies = coordinates(maps, outputs, dimension, work);
            var direct = AffineObservationReduction.coordinatePlan(maps, outputs, dependencies, dimension, work);
            int width = dependencies.size();
            long setupEstimate = 6L * maps.size() * (dimension + 1L) * width * width;
            long potentialSaving = (long) expectedEvents * width * Math.max(0, width - outputs.size());
            if (setupEstimate > 0 && potentialSaving >= setupEstimate
                    && work.remaining() >= PROBE_BUDGET_DIVISOR) {
                int available = work.remaining() / PROBE_BUDGET_DIVISOR;
                var reduced = AffineObservationReduction.prepare(maps, outputs,
                    new Budget((int) Math.min(setupEstimate, available)));
                work.add(reduced.work().consumedWorkUnits());
                var plan = reduced.value().orElse(null);
                if (plan != null && plan.reducedDimension() < width) return new TracePlan(Strategy.REDUCED, plan);
            }
            return new TracePlan(Strategy.DIRECT, direct);
        });
    }

    private static boolean worthTryingIndexed(List<BigInteger> indices, int width, int expectedStarts, Work work) {
        if (indices.isEmpty() || work.remaining() < INDEXED_REDUCTION_SETUP_ESTIMATE) return false;
        // Estimate only the powers and pullbacks that could be shared across the batch.
        int maximumBits = 0;
        long projections = 0;
        var distinct = new TreeSet<BigInteger>((left, right) -> DirectIndexedObservation.compareIndices(left, right, work));
        for (var index : indices) {
            work.add(1L + index.bitLength() / Long.SIZE);
            if (distinct.add(index)) {
                maximumBits = Math.max(maximumBits, index.bitLength());
                if (index.signum() > 0) {
                    projections += distinct.contains(index.subtract(BigInteger.ONE)) ? 1 : index.bitCount();
                }
            }
        }
        long setup = ((long) Math.max(0, maximumBits - 1) * width + projections) * width * width;
        long evaluation = (long) indices.size() * (width - 1) * expectedStarts;
        return setup >= INDEXED_REDUCTION_SETUP_ESTIMATE
            || evaluation >= INDEXED_REDUCTION_SETUP_ESTIMATE - setup;
    }

    private static RecurrenceObservationKrylov.PreparedQueries tryIndexedReduction(Transition transition,
            Observation observation, List<BigInteger> indices, int width, int expectedStarts, Work work) {
        // Keep the exact scalar attempt bounded, including preparing all requested indices.
        var probe = new Work(work.remaining() / PROBE_BUDGET_DIVISOR);
        try {
            if (!worthTryingIndexed(indices, width, expectedStarts, probe)
                    || !isScalarObservation(transition, observation, probe)) return null;
            var recurrence = new RecurrenceInvariantSynthesis.Transition(transition.linear(), transition.offset());
            var coefficients = observation.weights().values();
            var polynomial = Polynomial.constant(observation.offset());
            for (int i = 0; i < coefficients.size(); i++) {
                probe.add(1);
                polynomial = polynomial.add(Polynomial.term(Monomial.variable("x" + i), coefficients.get(i)));
            }
            var verified = RecurrenceObservationKrylov.prepare(recurrence, polynomial, new Budget(probe.remaining()));
            probe.add(verified.work().consumedWorkUnits());
            if (verified.value().isEmpty() || verified.value().orElseThrow().order() != 1) return null;
            var batch = verified.value().orElseThrow().prepareQueries(indices, new Budget(probe.remaining()));
            probe.add(batch.work().consumedWorkUnits());
            return batch.value().orElse(null);
        } catch (Exhausted | ArithmeticLimit limit) {
            return null;
        } finally {
            work.add(probe.ledger().consumedWorkUnits());
        }
    }

    private static boolean isScalarObservation(Transition transition, Observation observation, Work work) {
        var weights = observation.weights().values();
        int dimension = weights.size();
        if (!fitsRecurrenceInput(weights, work) || !fitsRecurrenceInput(transition.offset().values(), work)
                || !fitsRecurrenceInput(List.of(observation.offset()), work)) return false;
        for (var row : transition.linear().rows()) {
            if (!fitsRecurrenceInput(row, work)) return false;
        }
        int pivot = 0;
        while (weights.get(pivot).isZero()) {
            work.add(1);
            pivot++;
        }
        var ratio = checked(pullbackCoefficient(transition, weights, pivot, work).divide(weights.get(pivot)));
        for (int column = 0; column < dimension; column++) {
            if (column != pivot && !pullbackCoefficient(transition, weights, column, work)
                    .equals(multiply(ratio, weights.get(column), work))) return false;
        }
        var offset = observation.offset();
        for (int row = 0; row < dimension; row++) offset = addProduct(offset, weights.get(row), transition.offset().get(row), work);
        return offset.equals(multiply(ratio, observation.offset(), work));
    }

    private static Rational pullbackCoefficient(Transition transition, List<Rational> weights, int column, Work work) {
        var total = Rational.ZERO;
        for (int row = 0; row < weights.size(); row++) {
            total = addProduct(total, weights.get(row), transition.linear().get(row, column), work);
        }
        return total;
    }

    private static boolean fitsRecurrenceInput(List<Rational> row, Work work) {
        for (var value : row) {
            work.add(1);
            if (value.numerator().bitLength() > 128 || value.denominator().bitLength() > 128) return false;
        }
        return true;
    }

    private static List<Rational> completedBatch(RecurrenceObservationKrylov.Result<List<Rational>> result, Work work) {
        work.add(result.work().consumedWorkUnits());
        return switch (result.status()) {
            case COMPLETE -> result.value().orElseThrow();
            case BUDGET_INCONCLUSIVE -> throw new Exhausted();
            case ARITHMETIC_LIMIT -> throw new ArithmeticLimit();
        };
    }

    private static List<Integer> coordinates(List<Transition> transitions, List<Observation> observations,
            int dimension, Work work) {
        work.add(dimension);
        var used = new boolean[dimension];
        var coordinates = new ArrayList<Integer>();
        for (var observation : observations) collect(observation.weights().values(), used, coordinates, work);
        for (int i = 0; i < coordinates.size(); i++) {
            for (var transition : transitions) collect(transition.linear().rows().get(coordinates.get(i)),
                used, coordinates, work);
        }
        return List.copyOf(coordinates);
    }

    private static void collect(List<Rational> weights, boolean[] used, List<Integer> coordinates, Work work) {
        work.add(1L + weights.size());
        for (int i = 0; i < weights.size(); i++) {
            if (!used[i] && !weights.get(i).isZero()) {
                used[i] = true;
                coordinates.add(i);
            }
        }
    }
}
