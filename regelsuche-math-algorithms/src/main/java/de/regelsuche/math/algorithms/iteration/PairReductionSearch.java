package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.PairReduction;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.SearchExecution;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Source-only search for a checked one-pass pair plan on the shared move frontier. */
public final class PairReductionSearch {
    private static final String NORMALIZE = "normalize-pair-summand";
    private static final String REDUCE = "reduce-pair-sum";
    private static final String WORK_LIMIT = "WORK_LIMIT";
    private static final PairReductionSynthesis SYNTHESIS = new PairReductionSynthesis();
    private static final PairReductionVerifier VERIFIER = new PairReductionVerifier();

    private PairReductionSearch() {}

    public record CheckedMove(String ruleId, long synthesisWork, long verificationWork) {}

    public record Result(Optional<PairReductionVerifier.Verified> verified, String reason,
            List<CheckedMove> witness, MoveSearch.Metrics metrics) {
        public Result {
            witness = List.copyOf(witness);
        }
    }

    public static Result search(PairReduction source, IterationLimits limits, int maximumStates,
            BooleanSupplier cancellation) {
        var run = new Run(source, limits, maximumStates, cancellation);
        var checked = SearchExecution.searchUntil(run,
            state -> new MoveSearch.ObjectiveScore(state.expression() instanceof Reduced ? 0 : 1, 1), 0);
        return run.result(checked);
    }

    private sealed interface Form permits Source, Normalized, Reduced {}
    private record Source(PairReduction value) implements Form {}
    private record Normalized(PairReductionSynthesis.NormalForm value) implements Form {}
    private record Reduced(PairReductionPlan value) implements Form {}

    private record State(Form expression, int searchDepth, int primitiveDepth, String previousRule,
            List<String> assumptions, Set<String> capabilities, int complexityDebt)
            implements SearchExecution.Position<Form> {}

    private record Move(Form source, Form targetExpression, String ruleId, ExecutionWork executionWork)
            implements SearchExecution.Edge<Form, Move> {
        @Override public int primitiveStepCount() { return 0; }
        @Override public String ruleFamily() { return "pair-reduction"; }
        @Override public List<String> assumptions() { return List.of(); }
        @Override public Move withCapabilityDelta(Set<String> delta) { return this; }
        @Override public void requireSource(Form expression) {
            if (!source.equals(expression)) {
                throw new IllegalArgumentException("pair proposal differs from its source");
            }
        }
    }

    private record Verification(boolean accepted, long work, Optional<PairReductionVerifier.Verified> verified)
            implements SearchExecution.Verification {}

    private enum Assessment implements SearchExecution.Assessment<Form> {
        EMPTY;
        @Override public int complexity() { return 0; }
        @Override public double value() { return 0; }
        @Override public long searchWork() { return 0; }
        @Override public long primitiveWork() { return 0; }
        @Override public Map<String, SearchExecution.Capability<Form>> capabilities() { return Map.of(); }
    }

    private static final class Run implements SearchExecution.Environment<Form, State, Move, Assessment, Verification> {
        private final Source source;
        private final IterationLimits limits;
        private final MoveSearch.Budget budget;
        private final BooleanSupplier cancellation;
        private final long start = System.nanoTime();
        private final long timeout;
        private String reason = "NO_PAIR_PLAN";

        Run(PairReduction source, IterationLimits limits, int maximumStates, BooleanSupplier cancellation) {
            this.source = new Source(Objects.requireNonNull(source));
            this.limits = Objects.requireNonNull(limits);
            this.cancellation = Objects.requireNonNull(cancellation);
            budget = new MoveSearch.Budget(0, 2, limits.maximumWork(), maximumStates, limits.maximumWork());
            timeout = TimeUnit.MILLISECONDS.toNanos(limits.timeoutMillis());
        }

        @Override public Form source() { return source; }
        @Override public Form goal() { return null; }
        @Override public List<String> initialAssumptions() { return List.of(); }
        @Override public MoveSearch.Budget budget() { return budget; }
        @Override public MoveSearch.Mode mode() { return MoveSearch.Mode.FAST; }
        @Override public MoveSearch.Scheduling scheduling() { return MoveSearch.Scheduling.INCREMENTAL_NATIVE_ORDER; }

        @Override public State state(Form form, int depth, int primitive, String previous, List<String> assumptions,
                Set<String> capabilities, int debt) {
            return new State(form, depth, primitive, previous, assumptions, capabilities, debt);
        }

        @Override public Assessment inspect(State state) { return Assessment.EMPTY; }
        @Override public double score(State state) { return -state.searchDepth(); }
        @Override public boolean carries(List<String> assumptions, State state) { return assumptions.isEmpty(); }
        @Override public SearchExecution.Picker<Move> picker(State state) { return new PairPicker(this, state.expression()); }

        @Override public Verification verify(State state, Move move) {
            return verify(state, move, limits.maximumWork());
        }

        @Override public Verification verify(State state, Move move, long allowance) {
            move.requireSource(state.expression());
            var remaining = limits.withMaximumWork(allowance);
            return switch (move.targetExpression()) {
                case Normalized normalized -> checkNormalization(normalized, remaining);
                case Reduced reduced -> checkPlan(reduced, remaining);
                case Source ignored -> throw new IllegalArgumentException("unexpected pair proposal");
            };
        }

        private Verification checkNormalization(Normalized normalized, IterationLimits remaining) {
            var check = VERIFIER.verifyNormalization(source.value(), normalized.value(), remaining, this::interrupted);
            if (!check.accepted()) reason = check.reason();
            return new Verification(check.accepted(), check.work(), Optional.empty());
        }

        private Verification checkPlan(Reduced reduced, IterationLimits remaining) {
            var check = VERIFIER.verify(source.value(), reduced.value(), remaining, this::interrupted);
            if (check.verified().isEmpty()) reason = check.reason();
            return new Verification(check.verified().isPresent(), check.work(), check.verified());
        }

        private boolean interrupted() { return !interruptionReason().isEmpty(); }

        private String interruptionReason() {
            if (cancellation.getAsBoolean() || Thread.currentThread().isInterrupted()) return "CANCELLED";
            return System.nanoTime() - start >= timeout ? WORK_LIMIT : "";
        }

        Result result(SearchExecution.CheckedPath<State, Move, Verification> path) {
            String interrupted = interruptionReason();
            if (!interrupted.isEmpty()) return unavailable(interrupted, path.metrics());
            if (path.outcome() == MoveSearch.Outcome.QUALITY_REACHED) {
                var steps = path.witness();
                var verified = steps.getLast().verification().verified().orElseThrow();
                var witness = steps.stream().map(step -> new CheckedMove(step.move().ruleId(),
                    step.move().executionWork().canonicalWorkUnits(), step.verification().work())).toList();
                return new Result(Optional.of(verified), "VERIFIED", witness, path.metrics());
            }
            return unavailable(switch (path.outcome()) {
                case WORK_EXHAUSTED -> WORK_LIMIT;
                case STATE_LIMIT -> "STATE_LIMIT";
                default -> reason;
            }, path.metrics());
        }

        private static Result unavailable(String reason, MoveSearch.Metrics metrics) {
            return new Result(Optional.empty(), reason, List.of(), metrics);
        }
    }

    /** Each rule opens one bounded candidate for the typed state it accepts. */
    private static final class PairPicker implements SearchExecution.Picker<Move> {
        private static final long DISPATCH_WORK = TransformationWorkMetrics.flatEngine(1).totalWorkUnits();
        private final Run run;
        private final Form source;
        private List<Move> generated = List.of();
        private TransformationWorkMetrics metrics = TransformationWorkMetrics.ZERO;
        private boolean attempted;
        private boolean exhausted;

        PairPicker(Run run, Form source) {
            this.run = run;
            this.source = source;
        }

        @Override public boolean incremental() { return true; }
        @Override public Object executionReceipt() { return metrics; }
        @Override public boolean complete() { return attempted && !exhausted; }
        @Override public boolean workExhausted() { return exhausted; }
        @Override public List<Move> generatedMoves() { return generated; }
        @Override public TransformationWorkMetrics workMetrics() { return metrics; }
        @Override public Optional<Move> next() { return next(run.limits.maximumWork()); }

        @Override public Optional<Move> next(long allowance) {
            if (attempted) return Optional.empty();
            attempted = true;
            if (allowance <= DISPATCH_WORK) {
                exhausted = true;
                return Optional.empty();
            }
            var remaining = run.limits.withMaximumWork(allowance - DISPATCH_WORK);
            return switch (source) {
                case Source input -> candidate(NORMALIZE,
                    SYNTHESIS.normalize(input.value(), remaining, run::interrupted), Normalized::new);
                case Normalized normalized -> candidate(REDUCE,
                    SYNTHESIS.reduce(normalized.value(), remaining, run::interrupted), Reduced::new);
                case Reduced ignored -> Optional.empty();
            };
        }

        private <T> Optional<Move> candidate(String rule, PairReductionSynthesis.Result<T> result,
                java.util.function.Function<T, Form> target) {
            var work = result.work() == 0 ? ExecutionWork.ZERO : new ExecutionWork(0, 1, result.work());
            var move = result.value().map(value -> new Move(source, target.apply(value), rule, work));
            generated = move.stream().toList();
            metrics = TransformationWorkMetrics.flatEngine(generated.size()).withCandidateWork(work);
            if (move.isEmpty()) {
                run.reason = result.reason();
                exhausted = WORK_LIMIT.equals(run.reason);
            }
            return move;
        }
    }
}
