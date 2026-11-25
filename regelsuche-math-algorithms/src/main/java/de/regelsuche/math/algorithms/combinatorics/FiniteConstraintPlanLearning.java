package de.regelsuche.math.algorithms.combinatorics;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Problem;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintPlanner.Strategy;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.planning.BoundedPlanSelection;
import de.regelsuche.math.algorithms.planning.BoundedPlanSelection.Checked;
import de.regelsuche.math.algorithms.planning.BoundedPlanSelection.Estimate;
import de.regelsuche.math.algorithms.planning.BoundedPlanSelection.Kind;
import de.regelsuche.math.algorithms.planning.BoundedPlanSelection.Offer;
import de.regelsuche.math.algorithms.planning.BoundedPlanSelection.Offers;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;

/** Selects an ordering heuristic using the observed costs of verified counts on earlier sources. */
public final class FiniteConstraintPlanLearning {
    private FiniteConstraintPlanLearning() {}

    // One feature vector, observation, immutable row list, and one small outcome per heuristic.
    private static final long OBSERVATION_CELLS = 20L + 6L * Strategy.values().length;

    public record Example(String source, Problem problem) {
        public Example {
            if (Objects.requireNonNull(source).isBlank()) throw new IllegalArgumentException("EMPTY_SOURCE");
            Objects.requireNonNull(problem);
        }
    }

    /** Logarithmic size/pressure bins and the maximum residual arity, computed from free scopes alone. */
    public record Features(int freeSize, int factorCount, int maxArity, int meanIncidence,
            int maxForwardIncidence) {
        private int distance(Features other) {
            return Math.abs(freeSize - other.freeSize) + Math.abs(factorCount - other.factorCount)
                + Math.abs(maxArity - other.maxArity) + Math.abs(meanIncidence - other.meanIncidence)
                + Math.abs(maxForwardIncidence - other.maxForwardIncidence);
        }
    }

    public record Trial(Strategy strategy, FiniteConstraintCounter.Status status, String reason, long work) {}
    public record Observation(String source, Features features, List<Trial> trials, Optional<Strategy> best) {
        public Observation { trials = List.copyOf(trials); }
    }

    /** A model is only created from a completed training call. Training data and outcomes are immutable. */
    public static final class Model {
        private final List<Observation> observations;
        private final long trainingWork;
        private final long retainedCells;

        private Model(List<Observation> observations, long trainingWork, long retainedCells) {
            this.observations = List.copyOf(observations);
            this.trainingWork = trainingWork;
            this.retainedCells = retainedCells;
        }

        public List<Observation> observations() { return observations; }
        public long trainingWork() { return trainingWork; }
    }

    public record Training(Optional<Model> model, List<Observation> observations, long work, String reason) {
        public Training { observations = List.copyOf(observations); }
    }
    public record Decision(Strategy strategy, String trainingSource, int featureDistance) {}
    /** work includes feature extraction, policy selection, counting, and certificate verification. */
    public record Evaluation(Optional<Decision> decision, FiniteConstraintCounter.Result result, long work) {}

    /** Each strategy gets the same work allowance; the whole experiment also has an outer limit. */
    public static Training train(List<Example> examples, IterationLimits totalLimits, long perTrialWork,
            BooleanSupplier cancellation) {
        Objects.requireNonNull(examples);
        if (perTrialWork < 1) throw new IllegalArgumentException("INVALID_TRIAL_WORK");
        var work = new CertificateWork(totalLimits, cancellation);
        var observations = new ArrayList<Observation>();
        long nestedWork = 0;
        try {
            // Retain the input/list copy, hash-set entries, size table, and all completed observations.
            long batchCells = 8L + 7L * examples.size();
            long modelCells = 4L + OBSERVATION_CELLS * examples.size();
            work.cells(batchCells + modelCells);
            work.charge(examples.size());
            var inputs = List.copyOf(examples);
            var sizes = new long[inputs.size()];
            var sources = new HashSet<String>();
            for (int i = 0; i < inputs.size(); i++) {
                Example example = inputs.get(i);
                work.charge(1L + example.source().length());
                if (!sources.add(example.source())) throw new IllegalArgumentException("DUPLICATE_TRAINING_SOURCE");
                sizes[i] = sourceCells(example.problem(), work);
                batchCells += sizes[i];
                modelCells += example.source().length();
                work.cells(batchCells + modelCells);
            }
            long retained = batchCells + modelCells;
            boolean found = false;
            for (int i = 0; i < inputs.size(); i++) {
                Example example = inputs.get(i);
                Features features = features(example.problem(), retained, work);
                var trials = new ArrayList<Trial>();
                BigInteger reference = null;
                Trial best = null;
                for (var strategy : Strategy.values()) {
                    work.charge(1);
                    var remaining = work.remainingLimits(retained - sizes[i]);
                    if (remaining.maximumWork() < perTrialWork) throw new CertificateWork.Stop("TRAINING_WORK_LIMIT");
                    var result = FiniteConstraintCounter.count(example.problem(), strategy,
                        remaining.withMaximumWork(perTrialWork), cancellation);
                    nestedWork = result.work();
                    work.charge(nestedWork);
                    nestedWork = 0;
                    if ("CANCELLED".equals(result.reason()) || "TIME_LIMIT".equals(result.reason()))
                        throw new CertificateWork.Stop(result.reason());
                    var trial = new Trial(strategy, result.status(), result.reason(), result.work());
                    trials.add(trial);
                    if (result.status() == FiniteConstraintCounter.Status.EXACT) {
                        var count = result.count().orElseThrow();
                        if (reference != null && !reference.equals(count)) throw new IllegalStateException("INCONSISTENT_COUNTS");
                        reference = count;
                        if (best == null || trial.work() < best.work()) best = trial;
                    }
                }
                found |= best != null;
                observations.add(new Observation(example.source(), features, trials, Optional.ofNullable(best).map(Trial::strategy)));
            }
            work.charge(observations.size());
            if (!found) return new Training(Optional.empty(), observations, work.used(), "NO_VERIFIED_TRAINING_TRIAL");
            var model = new Model(observations, work.used(), modelCells);
            return new Training(Optional.of(model), observations, work.used(), "TRAINED");
        } catch (CertificateWork.Stop stop) {
            return new Training(Optional.empty(), observations, work.used() + nestedWork, stop.getMessage());
        }
    }

    /** The first closest successful training source breaks ties. Each evaluation performs a fresh verified count. */
    public static Evaluation count(Model model, Problem problem, IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(model);
        Objects.requireNonNull(problem);
        var work = new CertificateWork(limits, cancellation);
        Decision decision = null;
        long nestedWork = 0;
        try {
            Features features = features(problem, model.retainedCells + sourceCells(problem, work), work);
            for (Observation observation : model.observations()) {
                work.charge(1);
                if (observation.best().isEmpty()) continue;
                work.charge(5);
                int distance = features.distance(observation.features());
                if (decision == null || distance < decision.featureDistance())
                    decision = new Decision(observation.best().orElseThrow(), observation.source(), distance);
            }
            var result = FiniteConstraintCounter.count(problem, Objects.requireNonNull(decision).strategy(),
                work.remainingLimits(model.retainedCells), cancellation);
            nestedWork = result.work();
            work.charge(nestedWork);
            return new Evaluation(Optional.of(decision), result, work.used());
        } catch (CertificateWork.Stop stop) {
            var unavailable = new FiniteConstraintCounter.Result(FiniteConstraintCounter.Status.UNAVAILABLE,
                Optional.empty(), Optional.empty(), stop.getMessage(), nestedWork);
            return new Evaluation(Optional.ofNullable(decision), unavailable, work.used() + nestedWork);
        }
    }

    /** The free variable and input factor counts are reported even for fixed-only calls. */
    public record Size(int freeVariables, int factors) {}

    /**
     * Compare the default min-degree order with an earlier observed order. Only an identical structural
     * feature vector with a successful fixed control can justify a probe. The learned estimate includes
     * this lookup and the acquisition cost amortized over the caller's expected number of evaluations.
     * The verified count and any fallback are charged at their actual work on the current source.
     */
    public static BoundedPlanSelection.Decision<Size, FiniteConstraintCounter.Result> countWithPolicy(
            Optional<Model> model, Problem problem, long expectedUses, long maximumProbeWork,
            IterationLimits limits, BooleanSupplier cancellation) {
        if (expectedUses < 1) throw new IllegalArgumentException("INVALID_EXPECTED_USES");
        var size = new Size(problem.variableCount() - problem.fixed().size(), problem.factors().size());
        return BoundedPlanSelection.choose(size, BoundedPlanSelection.Unit.CERTIFICATE_WORK,
            model.map(Model::trainingWork).orElse(0L), maximumProbeWork, limits, cancellation, work -> {
                var fixed = countOffer(Strategy.MIN_DEGREE, Kind.FIXED, "GENERAL_ORDER", OptionalLong.empty(),
                    problem, cancellation);
                if (model.isEmpty()) return new Offers<>(fixed, Optional.empty(), 0);
                Model trained = model.orElseThrow();
                if (maximumProbeWork == 0) return new Offers<>(fixed, Optional.empty(), trained.retainedCells);
                long retained = BoundedPlanSelection.TRACE_CELLS + trained.retainedCells + sourceCells(problem, work);
                var current = features(problem, retained, work);
                for (var observation : trained.observations()) {
                    work.charge(6);
                    if (!current.equals(observation.features()) || observation.best().isEmpty()) continue;
                    Trial baseline = null;
                    Trial best = null;
                    for (var trial : observation.trials()) {
                        work.charge(1);
                        if (trial.status() != FiniteConstraintCounter.Status.EXACT) continue;
                        if (trial.strategy() == Strategy.MIN_DEGREE) baseline = trial;
                        if (trial.strategy() == observation.best().orElseThrow()) best = trial;
                    }
                    if (baseline == null || best == null) continue;
                    fixed = countOffer(Strategy.MIN_DEGREE, Kind.FIXED, observation.source(),
                        OptionalLong.of(baseline.work()), problem, cancellation);
                    long predicted = saturatedSum(best.work(), Math.ceilDiv(trained.trainingWork(), expectedUses));
                    predicted = saturatedSum(predicted, work.used());
                    var learned = countOffer(best.strategy(), Kind.LEARNED, observation.source(),
                        OptionalLong.of(predicted), problem, cancellation);
                    return new Offers<>(fixed, Optional.of(learned), trained.retainedCells);
                }
                var unseen = countOffer(Strategy.MIN_DEGREE, Kind.LEARNED, "NO_MATCHING_VERIFIED_CONTROL",
                    OptionalLong.empty(), problem, cancellation);
                return new Offers<>(fixed, Optional.of(unseen), trained.retainedCells);
            });
    }

    private static long saturatedSum(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static Offer<FiniteConstraintCounter.Result> countOffer(Strategy strategy, Kind kind, String evidence,
            OptionalLong estimate, Problem problem, BooleanSupplier cancellation) {
        return new Offer<>(new Estimate(strategy.name(), kind, evidence, estimate), allowance -> {
            var result = FiniteConstraintCounter.count(problem, strategy, allowance, cancellation);
            var exact = result.status() == FiniteConstraintCounter.Status.EXACT ? Optional.of(result)
                : Optional.<FiniteConstraintCounter.Result>empty();
            return new Checked<>(exact, result.reason(), result.work());
        });
    }

    // Source descriptors and scalar cells match the counter's input accounting; values are never inspected.
    private static long sourceCells(Problem problem, CertificateWork work) {
        long cells = problem.variableCount() + 2L * problem.fixed().size() + problem.factors().size();
        for (var factor : problem.factors()) {
            work.charge(1);
            cells += factor.variables().size() + (long) factor.values().size();
        }
        return cells;
    }

    private static Features features(Problem problem, long retained, CertificateWork work) {
        int free = problem.variableCount() - problem.fixed().size();
        work.cells(retained + problem.variableCount());
        work.charge(1L + problem.variableCount());
        long[] forward = new long[problem.variableCount()];
        long peak = 0;
        long total = 0;
        int maxArity = 0;
        int active = 0;
        for (var factor : problem.factors()) {
            work.charge(1);
            int arity = 0;
            for (int i = factor.variables().size() - 1; i >= 0; i--) {
                work.charge(1);
                int variable = factor.variables().get(i);
                if (problem.fixed().containsKey(variable)) continue;
                forward[variable] += arity;
                peak = Math.max(peak, forward[variable]);
                arity++;
            }
            if (arity > 0) active++;
            maxArity = Math.max(maxArity, arity);
            total += arity;
        }
        // Factors have ordered scopes. Forward incidences estimate the pressure of the source order.
        return new Features(bin(free), bin(active), maxArity, bin(total / Math.max(1, free)), bin(peak));
    }

    private static int bin(long value) { return Long.SIZE - Long.numberOfLeadingZeros(value); }
}
