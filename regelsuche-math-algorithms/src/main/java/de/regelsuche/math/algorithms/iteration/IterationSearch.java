package de.regelsuche.math.algorithms.iteration;

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

/** Adapts bounded iteration procedures to the shared move frontier. */
final class IterationSearch {
    private static final String WORK_LIMIT = "WORK_LIMIT";

    private IterationSearch() {}

    interface Domain<E, V> {
        String family();
        String noPlanReason();
        int maximumDepth();
        boolean finished(E state);
        Proposal<E> propose(E source, IterationLimits limits, BooleanSupplier cancellation);
        Check<V> verify(E source, E target, IterationLimits limits, BooleanSupplier cancellation);
    }

    record Proposal<E>(String ruleId, Optional<E> target, String reason, long work) {}

    record Check<V>(boolean accepted, Optional<V> verified, String reason, long work)
            implements SearchExecution.Verification {}

    record CheckedMove(String ruleId, long synthesisWork, long verificationWork) {}

    record Result<V>(Optional<V> verified, String reason, List<CheckedMove> witness, MoveSearch.Metrics metrics) {}

    static <E, V> Result<V> search(E source, Domain<E, V> domain, IterationLimits limits, int maximumStates,
            BooleanSupplier cancellation) {
        var run = new Run<>(source, domain, limits, maximumStates, cancellation);
        var path = SearchExecution.searchUntil(run,
            state -> new MoveSearch.ObjectiveScore(domain.finished(state.expression()) ? 0 : 1, 1), 0);
        return run.result(path);
    }

    private record State<E>(E expression, int searchDepth, int primitiveDepth, String previousRule,
            List<String> assumptions, Set<String> capabilities, int complexityDebt)
            implements SearchExecution.Position<E> {}

    private record Move<E>(E source, E targetExpression, String ruleId, String ruleFamily, ExecutionWork executionWork)
            implements SearchExecution.Edge<E, Move<E>> {
        @Override public int primitiveStepCount() { return 0; }
        @Override public List<String> assumptions() { return List.of(); }
        @Override public Move<E> withCapabilityDelta(Set<String> delta) { return this; }
        @Override public void requireSource(E expression) {
            if (!source.equals(expression)) {
                throw new IllegalArgumentException("iteration proposal differs from its source");
            }
        }
    }

    private static final class Assessment<E> implements SearchExecution.Assessment<E> {
        @Override public int complexity() { return 0; }
        @Override public double value() { return 0; }
        @Override public long searchWork() { return 0; }
        @Override public long primitiveWork() { return 0; }
        @Override public Map<String, SearchExecution.Capability<E>> capabilities() { return Map.of(); }
    }

    private static final class Run<E, V>
            implements SearchExecution.Environment<E, State<E>, Move<E>, Assessment<E>, Check<V>> {
        private final E source;
        private final Domain<E, V> domain;
        private final IterationLimits limits;
        private final MoveSearch.Budget budget;
        private final Assessment<E> assessment = new Assessment<>();
        private final BooleanSupplier cancellation;
        private final long start = System.nanoTime();
        private final long timeout;
        private String reason;

        Run(E source, Domain<E, V> domain, IterationLimits limits, int maximumStates, BooleanSupplier cancellation) {
            this.source = Objects.requireNonNull(source);
            this.domain = domain;
            this.limits = Objects.requireNonNull(limits);
            this.cancellation = Objects.requireNonNull(cancellation);
            budget = new MoveSearch.Budget(0, domain.maximumDepth(), limits.maximumWork(), maximumStates, limits.maximumWork());
            timeout = TimeUnit.MILLISECONDS.toNanos(limits.timeoutMillis());
            reason = domain.noPlanReason();
        }

        @Override public E source() { return source; }
        @Override public E goal() { return null; }
        @Override public List<String> initialAssumptions() { return List.of(); }
        @Override public MoveSearch.Budget budget() { return budget; }
        @Override public MoveSearch.Mode mode() { return MoveSearch.Mode.FAST; }
        @Override public MoveSearch.Scheduling scheduling() { return MoveSearch.Scheduling.INCREMENTAL_NATIVE_ORDER; }

        @Override public State<E> state(E expression, int depth, int primitive, String previous,
                List<String> assumptions, Set<String> capabilities, int debt) {
            return new State<>(expression, depth, primitive, previous, assumptions, capabilities, debt);
        }

        @Override public Assessment<E> inspect(State<E> state) { return assessment; }
        @Override public double score(State<E> state) { return -state.searchDepth(); }
        @Override public boolean carries(List<String> assumptions, State<E> state) { return assumptions.isEmpty(); }
        @Override public SearchExecution.Picker<Move<E>> picker(State<E> state) { return new RulePicker<>(this, state.expression()); }

        @Override public Check<V> verify(State<E> state, Move<E> move) {
            return verify(state, move, limits.maximumWork());
        }

        @Override public Check<V> verify(State<E> state, Move<E> move, long allowance) {
            move.requireSource(state.expression());
            var check = domain.verify(state.expression(), move.targetExpression(),
                limits.withMaximumWork(allowance), this::interrupted);
            if (!check.accepted()) reason = check.reason();
            return check;
        }

        private boolean interrupted() { return !interruptionReason().isEmpty(); }

        private String interruptionReason() {
            if (cancellation.getAsBoolean() || Thread.currentThread().isInterrupted()) return "CANCELLED";
            return System.nanoTime() - start >= timeout ? WORK_LIMIT : "";
        }

        Result<V> result(SearchExecution.CheckedPath<State<E>, Move<E>, Check<V>> path) {
            String interrupted = interruptionReason();
            if (!interrupted.isEmpty()) return unavailable(interrupted, path.metrics());
            if (path.outcome() == MoveSearch.Outcome.QUALITY_REACHED) {
                var steps = path.witness();
                var verified = steps.getLast().verification().verified().orElseThrow();
                var witness = steps.stream().map(step -> new CheckedMove(step.move().ruleId(),
                    step.move().executionWork().canonicalWorkUnits(), step.verification().work())).toList();
                return new Result<>(Optional.of(verified), "VERIFIED", witness, path.metrics());
            }
            return unavailable(switch (path.outcome()) {
                case WORK_EXHAUSTED -> WORK_LIMIT;
                case STATE_LIMIT -> "STATE_LIMIT";
                default -> reason;
            }, path.metrics());
        }

        private Result<V> unavailable(String reason, MoveSearch.Metrics metrics) {
            return new Result<>(Optional.empty(), reason, List.of(), metrics);
        }
    }

    /** A typed iteration procedure proposes at most one move from each state. */
    private static final class RulePicker<E, V> implements SearchExecution.Picker<Move<E>> {
        private static final long DISPATCH_WORK = TransformationWorkMetrics.flatEngine(1).totalWorkUnits();
        private final Run<E, V> run;
        private final E source;
        private List<Move<E>> generated = List.of();
        private TransformationWorkMetrics metrics = TransformationWorkMetrics.ZERO;
        private boolean attempted;
        private boolean exhausted;

        RulePicker(Run<E, V> run, E source) {
            this.run = run;
            this.source = source;
        }

        @Override public boolean incremental() { return true; }
        @Override public Object executionReceipt() { return metrics; }
        @Override public boolean complete() { return attempted && !exhausted; }
        @Override public boolean workExhausted() { return exhausted; }
        @Override public List<Move<E>> generatedMoves() { return generated; }
        @Override public TransformationWorkMetrics workMetrics() { return metrics; }
        @Override public Optional<Move<E>> next() { return next(run.limits.maximumWork()); }

        @Override public Optional<Move<E>> next(long allowance) {
            if (attempted) return Optional.empty();
            attempted = true;
            if (allowance <= DISPATCH_WORK) {
                exhausted = true;
                return Optional.empty();
            }
            var remaining = run.limits.withMaximumWork(allowance - DISPATCH_WORK);
            return candidate(run.domain.propose(source, remaining, run::interrupted));
        }

        private Optional<Move<E>> candidate(Proposal<E> proposal) {
            var work = proposal.work() == 0 ? ExecutionWork.ZERO : new ExecutionWork(0, 1, proposal.work());
            var move = proposal.target().map(target -> new Move<>(source, target, proposal.ruleId(), run.domain.family(), work));
            generated = move.stream().toList();
            metrics = TransformationWorkMetrics.flatEngine(generated.size()).withCandidateWork(work);
            if (move.isEmpty()) {
                run.reason = proposal.reason();
                exhausted = WORK_LIMIT.equals(run.reason);
            }
            return move;
        }
    }
}
