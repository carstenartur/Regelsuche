package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.add;
import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.addProduct;
import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.checked;
import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.multiply;
import static de.regelsuche.math.algorithms.linalg.SparseAffineForm.evaluateAll;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Exhausted;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Work;
import de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.ArithmeticLimit;
import de.regelsuche.math.algorithms.linalg.SparseAffineForm.Update;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.representation.RepresentationBridge.WorkLedger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Derives an exact minimal affine-linear state for fixed observations of labelled affine maps over Q. */
public final class AffineObservationReduction {
    private AffineObservationReduction() { }

    public record Transition(ExactMatrix linear, ExactVector offset) {
        public Transition {
            Objects.requireNonNull(linear, "linear");
            Objects.requireNonNull(offset, "offset");
            if (linear.rowCount() != linear.columns() || offset.dimension() != linear.rowCount()) {
                throw new IllegalArgumentException("transition must be square with a matching offset");
            }
        }
    }

    public record Observation(ExactVector weights, Rational offset) {
        public Observation {
            Objects.requireNonNull(weights, "weights");
            Objects.requireNonNull(offset, "offset");
        }
    }

    public enum Status { COMPLETE, BUDGET_INCONCLUSIVE, ARITHMETIC_LIMIT }

    /** Only a completed phase publishes its value. Work includes any unsuccessful attempt. */
    public record Result<T>(Status status, Optional<T> value, WorkLedger work) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(work, "work");
            if ((status == Status.COMPLETE) != value.isPresent()) {
                throw new IllegalArgumentException("only a complete result has a value");
            }
        }
    }

    /**
     * Closes the nonconstant observation forms under pullback by every transition.
     * Each inserted row is a residual of an observation or a pullback of an earlier row.
     * Its new pivot makes it independent. Any closed observation space must contain
     * every one of these rows. Offsets are known constants and require no state slot.
     *
     * <p>Transition indices follow input list order. Observations must be nonempty.
     * The exact arithmetic uses the recurrence observation module's rational size bound.
     * Preparation checks the resulting affine identities at zero and each unit vector.
     * To share a budget across phases, pass the previous phase's remaining work as the next budget.</p>
     */
    public static Result<VerifiedPlan> prepare(List<Transition> transitions, List<Observation> observations,
            Budget budget) {
        Objects.requireNonNull(transitions, "transitions");
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) throw new IllegalArgumentException("at least one observation is required");
        return measured(budget, work -> {
            work.add(1L + transitions.size() + observations.size());
            return derive(List.copyOf(transitions), List.copyOf(observations), work);
        });
    }

    /** A preparation can be reused for any initial state in the source coordinates. */
    public static final class VerifiedPlan {
        private final int sourceDimension;
        private final List<ExactVector> basis;
        private final List<SparseAffineForm> projection;
        private final List<Update> updates;
        private final List<SparseAffineForm> outputs;

        private VerifiedPlan(int sourceDimension, List<ExactVector> basis, List<SparseAffineForm> projection,
                List<Update> updates, List<SparseAffineForm> outputs) {
            this.sourceDimension = sourceDimension;
            this.basis = basis;
            this.projection = projection;
            this.updates = updates;
            this.outputs = outputs;
        }

        public int sourceDimension() {
            return sourceDimension;
        }

        public int reducedDimension() {
            return basis.size();
        }

        /** The state coordinates are the values of these linear forms, in discovery order. */
        public List<ExactVector> basis() {
            return basis;
        }

        public Result<BoundState> bind(ExactVector state, Budget budget) {
            Objects.requireNonNull(state, "state");
            if (state.dimension() != sourceDimension) throw new IllegalArgumentException("initial state dimension mismatch");
            return measured(budget, work -> {
                checkCoefficients(state.values(), work);
                return new BoundState(this, evaluateAll(projection, state.values(), work));
            });
        }
    }

    /** Immutable state of a verified plan. A failed or successful query leaves this instance reusable. */
    public static final class BoundState {
        private final VerifiedPlan plan;
        private final List<Rational> state;

        private BoundState(VerifiedPlan plan, List<Rational> state) {
            this.plan = plan;
            this.state = state;
        }

        public Result<List<Rational>> observe(Budget budget) {
            return measured(budget, work -> evaluateAll(plan.outputs, state, work));
        }

        public Result<BoundState> advance(int transition, Budget budget) {
            return measured(budget, work -> {
                work.add(1L + state.size());
                var next = new ArrayList<>(state);
                update(transition, work).apply(next, work);
                return new BoundState(plan, next);
            });
        }

        /**
         * Reads each label once in event order and returns observations immediately after each event.
         * Retrying after failure with a single-use source requires replaying the source from the start.
         */
        public Result<Trace> trace(Iterable<Integer> transitions, Budget budget) {
            Objects.requireNonNull(transitions, "transitions");
            return measured(budget, work -> {
                work.add(1L + state.size());
                var current = new ArrayList<>(state);
                var observations = new ArrayList<List<Rational>>();
                List<Rational> lastOutput = null;
                for (int transition : transitions) {
                    work.add(1);
                    var update = update(transition, work);
                    update.apply(current, work);
                    if (lastOutput == null || update.affectsOutputs()) {
                        lastOutput = evaluateAll(plan.outputs, current, work);
                    }
                    observations.add(lastOutput);
                }
                work.add(1L + observations.size());
                return new Trace(new BoundState(plan, current), observations);
            });
        }

        private Update update(int transition, Work work) {
            if (transition < 0 || transition >= plan.updates.size()) {
                throw new IllegalArgumentException("unknown transition index: " + transition);
            }
            work.add(1);
            return plan.updates.get(transition);
        }
    }

    /** Observations in event order, and the state from which the next event can run. */
    public record Trace(BoundState finalState, List<List<Rational>> observations) {
        public Trace {
            Objects.requireNonNull(finalState, "finalState");
            observations = observations.stream().map(List::copyOf).toList();
        }
    }

    private static VerifiedPlan derive(List<Transition> transitions, List<Observation> observations, Work work) {
        int dimension = observations.getFirst().weights().dimension();
        checkInputs(transitions, observations, dimension, work);
        var rows = new ArrayList<BasisRow>();
        var outputs = new ArrayList<SparseAffineForm>(observations.size());
        for (var observation : observations) {
            outputs.add(represent(observation.weights().values(), observation.offset(), rows, work));
        }

        var updates = new ArrayList<List<SparseAffineForm>>(transitions.size());
        for (int label = 0; label < transitions.size(); label++) updates.add(new ArrayList<>());
        for (int row = 0; row < rows.size(); row++) {
            var form = rows.get(row).weights().values();
            for (int label = 0; label < transitions.size(); label++) {
                var transition = transitions.get(label);
                var image = pullback(form, transition.linear(), work);
                var bias = Rational.ZERO;
                for (int i = 0; i < dimension; i++) {
                    bias = addProduct(bias, form.get(i), transition.offset().get(i), work);
                }
                updates.get(label).add(represent(image, bias, rows, work));
            }
        }

        work.add(1L + rows.size() + outputs.size() + (long) rows.size() * updates.size());
        var basis = rows.stream().map(BasisRow::weights).toList();
        var projection = new ArrayList<SparseAffineForm>(basis.size());
        for (var vector : basis) projection.add(SparseAffineForm.compile(vector.values(), Rational.ZERO, work));
        var preparedUpdates = new ArrayList<Update>(updates.size());
        for (var update : updates) preparedUpdates.add(Update.compile(update, outputs, work));
        var plan = new VerifiedPlan(dimension, basis, List.copyOf(projection),
            List.copyOf(preparedUpdates), List.copyOf(outputs));
        if (!verify(transitions, observations, plan, work)) {
            throw new IllegalStateException("independent affine observation proof rejected the derived state");
        }
        return plan;
    }

    private static void checkInputs(List<Transition> transitions, List<Observation> observations, int dimension, Work work) {
        for (var transition : transitions) {
            if (transition.linear().columns() != dimension) throw new IllegalArgumentException("transition dimension mismatch");
            for (var row : transition.linear().rows()) checkCoefficients(row, work);
            checkCoefficients(transition.offset().values(), work);
        }
        for (var observation : observations) {
            if (observation.weights().dimension() != dimension) throw new IllegalArgumentException("observation dimension mismatch");
            work.add(1);
            checked(observation.offset());
            checkCoefficients(observation.weights().values(), work);
        }
    }

    private static void checkCoefficients(List<Rational> coefficients, Work work) {
        work.add(coefficients.size());
        coefficients.forEach(RecurrenceObservationArithmetic::checked);
    }

    private static List<Rational> pullback(List<Rational> form, ExactMatrix linear, Work work) {
        work.add(form.size());
        var result = new ArrayList<Rational>(form.size());
        for (int column = 0; column < form.size(); column++) {
            var weight = Rational.ZERO;
            for (int row = 0; row < form.size(); row++) {
                weight = addProduct(weight, form.get(row), linear.get(row, column), work);
            }
            result.add(weight);
        }
        return result;
    }

    private static SparseAffineForm represent(List<Rational> weights, Rational offset, List<BasisRow> rows, Work work) {
        work.add(1L + weights.size());
        var residual = new ArrayList<>(weights);
        var coordinates = new ArrayList<Rational>();
        for (var row : rows) {
            work.add(1);
            var factor = residual.get(row.pivot());
            if (factor.isZero()) {
                coordinates.add(Rational.ZERO);
                continue;
            }
            var lead = row.weights().get(row.pivot());
            if (!lead.isOne()) {
                work.add(1);
                factor = checked(factor.divide(lead));
            }
            coordinates.add(factor);
            for (int i = 0; i < residual.size(); i++) {
                work.add(1);
                var coefficient = row.weights().get(i);
                if (!coefficient.isZero() && i != row.pivot()) {
                    var product = multiply(coefficient, factor, work);
                    work.add(1);
                    residual.set(i, checked(residual.get(i).subtract(product)));
                }
            }
            residual.set(row.pivot(), Rational.ZERO);
        }
        for (int pivot = 0; pivot < residual.size(); pivot++) {
            work.add(1);
            if (residual.get(pivot).isZero()) continue;
            work.add(1L + residual.size());
            // Keep the residual's scale so an independent observation is a state slot directly.
            rows.add(new BasisRow(pivot, new ExactVector(residual)));
            coordinates.add(Rational.ONE);
            break;
        }
        return SparseAffineForm.compile(coordinates, offset, work);
    }

    private static boolean verify(List<Transition> transitions, List<Observation> observations, VerifiedPlan plan, Work work) {
        int dimension = plan.sourceDimension();
        work.add(dimension);
        var sample = new ArrayList<>(Collections.nCopies(dimension, Rational.ZERO));
        for (int column = -1; column < dimension; column++) {
            work.add(1);
            if (column > 0) sample.set(column - 1, Rational.ZERO);
            if (column >= 0) sample.set(column, Rational.ONE);
            var reduced = evaluateAll(plan.projection, sample, work);
            for (int row = 0; row < plan.basis.size(); row++) {
                work.add(1);
                var coefficient = column < 0 ? Rational.ZERO : plan.basis.get(row).get(column);
                if (!coefficient.equals(reduced.get(row))) return false;
            }
            var output = evaluateAll(plan.outputs, reduced, work);
            for (int row = 0; row < observations.size(); row++) {
                work.add(1);
                var observation = observations.get(row);
                var expected = observation.offset();
                if (column >= 0) expected = add(expected, observation.weights().get(column), work);
                if (!expected.equals(output.get(row))) return false;
            }
            for (int label = 0; label < transitions.size(); label++) {
                var transition = transitions.get(label);
                var image = transition.offset().values();
                if (column >= 0) {
                    work.add(dimension);
                    var shifted = new ArrayList<Rational>(dimension);
                    for (int row = 0; row < dimension; row++) {
                        shifted.add(add(image.get(row), transition.linear().get(row, column), work));
                    }
                    image = shifted;
                }
                var expected = projectDirectly(plan.basis, image, work);
                work.add(1L + reduced.size());
                var actual = new ArrayList<>(reduced);
                plan.updates.get(label).apply(actual, work);
                if (!equal(expected, actual, work)) return false;
            }
        }
        return true;
    }

    private static List<Rational> projectDirectly(List<ExactVector> basis, List<Rational> state, Work work) {
        work.add(basis.size());
        var projected = new ArrayList<Rational>(basis.size());
        for (var form : basis) {
            var value = Rational.ZERO;
            for (int i = 0; i < state.size(); i++) value = addProduct(value, form.get(i), state.get(i), work);
            projected.add(value);
        }
        return projected;
    }

    private static boolean equal(List<Rational> expected, List<Rational> actual, Work work) {
        work.add(1L + expected.size());
        return expected.equals(actual);
    }

    private static <T> Result<T> measured(Budget budget, Function<Work, T> phase) {
        var work = new Work(Objects.requireNonNull(budget, "budget").maxWorkUnits());
        try {
            return new Result<>(Status.COMPLETE, Optional.of(phase.apply(work)), work.ledger());
        } catch (Exhausted exhausted) {
            return new Result<>(Status.BUDGET_INCONCLUSIVE, Optional.empty(), work.ledger());
        } catch (ArithmeticLimit tooLarge) {
            return new Result<>(Status.ARITHMETIC_LIMIT, Optional.empty(), work.ledger());
        }
    }

    private record BasisRow(int pivot, ExactVector weights) { }
}
