package de.regelsuche.search.moves;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;

/** A frontier of states AND suspended expansions. Old mechanical v1/v2 replay remains unchanged. */
public final class MoveSearch {
    public enum Mode { FAST, COMPLETE_BOUNDED_REFERENCE }
    public enum Scheduling { EAGER_CONTROL, STAGED, INCREMENTAL_NATIVE_ORDER, STAGED_INCREMENTAL }
    public enum Outcome { TARGET_REACHED, BOUNDED_EXHAUSTED, INCONCLUSIVE, WORK_EXHAUSTED, STATE_LIMIT, QUALITY_REACHED }
    public enum Decision { ENQUEUED, DUPLICATE, PATH_BOUND, ASSUMPTION_REJECTED, PROOF_REJECTED, COMPLEXITY_BOUND, WORK_LIMIT, DOMINATED }
    public record Budget(int maxPrimitiveSteps, int maxSearchDepth, long maxTheoryWork, int maxStates, long totalWork, int maxComplexityDebt) implements de.regelsuche.retention.RetainedGraph.View {
        @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v) {}
        public Budget(int primitive, int depth, long theory, int states, long work) { this(primitive, depth, theory, states, work, Integer.MAX_VALUE); }
        public Budget {
            if (maxPrimitiveSteps < 0 || maxSearchDepth < 0 || maxTheoryWork < 0 || maxStates < 1 || totalWork < 1 || maxComplexityDebt < 0)
                throw new IllegalArgumentException("invalid move search budget");
        }
    }
    public record Problem(String source, MoveContext context, List<MoveProvider> providers, MovePriorityPolicy policy,
            MoveVerifier verifier, ToDoubleFunction<MoveState> stateScore, Mode mode, Scheduling scheduling, Budget budget, StateValue stateValue) {
        public Problem(String source, MoveContext context, List<MoveProvider> providers, MovePriorityPolicy policy,
                MoveVerifier verifier, ToDoubleFunction<MoveState> score, Mode mode, Scheduling scheduling, Budget budget) {
            this(source, context, providers, policy, verifier, score, mode, scheduling, budget, StateValue.NONE);
        }
        public Problem {
            java.util.Objects.requireNonNull(context, "context");
            java.util.Objects.requireNonNull(providers, "providers");
            providers = List.copyOf(providers);
            java.util.Objects.requireNonNull(verifier); java.util.Objects.requireNonNull(policy);
            java.util.Objects.requireNonNull(stateScore); java.util.Objects.requireNonNull(mode);
            java.util.Objects.requireNonNull(scheduling); java.util.Objects.requireNonNull(budget); java.util.Objects.requireNonNull(stateValue);
            if (source == null || source.isBlank() || context.phase() == MoveContext.Phase.PRODUCTION)
                throw new IllegalArgumentException("experimental scheduling is not production-qualified (#745)");
            validateScheduling(scheduling, providers, policy);
        }
    }

    private static void validateScheduling(Scheduling scheduling, List<MoveProvider> providers, MovePriorityPolicy policy) {
        if (scheduling == Scheduling.INCREMENTAL_NATIVE_ORDER) {
            if (policy != MovePriorityPolicy.INVENTORY_ORDER || providers.stream().anyMatch(provider -> !(provider instanceof NativeIncrementalMoveProvider)))
                throw new IllegalArgumentException("incremental native ordering requires native providers and INVENTORY_ORDER");
        } else if (scheduling == Scheduling.STAGED_INCREMENTAL) {
            if (providers.stream().anyMatch(provider -> !StagedIncrementalSources.supported(provider)))
                throw new IllegalArgumentException("staged incremental requires an explicit supported provider contract");
        } else if (providers.stream().anyMatch(provider -> provider instanceof IncrementalMoveProvider)) {
            throw new IllegalArgumentException("incremental providers require INCREMENTAL_NATIVE_ORDER scheduling");
        }
    }
    /** The full attempted target identity is retained even when admission rejects it. */
    public record Event(MoveState source, MoveState target, SearchMove move, Decision decision, MoveVerifier.Verification verification) implements de.regelsuche.retention.RetainedGraph.View {
        @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(source);v.reference(target);v.reference(move);v.reference(decision);v.reference(verification);}

        /** Empty means NOT_PERFORMED, not a failed or free mathematical verification. JSON retains explicit null. */
        public java.util.Optional<MoveVerifier.Verification> verificationResult() { return java.util.Optional.ofNullable(verification); }
    }
    public record WitnessStep(MoveState source, MoveState target, SearchMove move, MoveVerifier.Verification verification) implements de.regelsuche.retention.RetainedGraph.View {
        @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(source);v.reference(target);v.reference(move);v.reference(verification);}
}
    public record Metrics(long generatedSuccessors, long consumedSuccessors, long discardedSuccessors, long unconsumedSuccessors,
            long duplicates, long deadEnds, long exploredStates, long expandedStates, long primitiveWork, long searchWork,
            long verificationWork, int firstHitDepth, int firstHitPrimitiveDepth, Map<String, Long> familyMatches) implements de.regelsuche.retention.RetainedGraph.View {
        @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(familyMatches);}
        public Metrics { familyMatches = de.regelsuche.retention.RetainedSortedMap.copyOf(familyMatches); }
        public long totalWork() { return Math.addExact(Math.addExact(primitiveWork, searchWork), verificationWork); }
        /** Explicit nonoverlapping charged dimensions; historical totals and serializers are unchanged. */
        public Map<String, Long> chargedComponents() {
            return Map.of("primitive", primitiveWork, "search", searchWork, "verification", verificationWork);
        }
        public double effectiveBranchingFactor() { return expandedStates == 0 ? 0 : (double) (consumedSuccessors - discardedSuccessors) / expandedStates; }
    }
    public record Result(Outcome outcome, List<WitnessStep> witness, List<Event> events, Set<MoveState> reachedStates, List<MoveState> deadEndStates,
            Metrics metrics, boolean completeBoundedRelation, Map<MoveState, StateValue.Assessment> stateAssessments,
            IncrementalMoveExecution incrementalExecution, StagedIncrementalMoveExecution stagedIncrementalExecution) implements de.regelsuche.retention.RetainedGraph.View {
        @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(outcome);v.reference(witness);v.reference(events);v.reference(reachedStates);v.reference(deadEndStates);v.reference(metrics);v.reference(stateAssessments);v.reference(incrementalExecution);v.reference(stagedIncrementalExecution);}

        public Result(Outcome outcome, List<WitnessStep> witness, List<Event> events, Set<MoveState> reachedStates,
                List<MoveState> deadEndStates, Metrics metrics, boolean completeBoundedRelation,
                Map<MoveState, StateValue.Assessment> stateAssessments, IncrementalMoveExecution incrementalExecution) {
            this(outcome, witness, events, reachedStates, deadEndStates, metrics, completeBoundedRelation,
                stateAssessments, incrementalExecution, null);
        }
        /** Historical constructor and exports retain their original work contract. */
        public Result(Outcome outcome, List<WitnessStep> witness, List<Event> events, Set<MoveState> reachedStates,
                List<MoveState> deadEndStates, Metrics metrics, boolean completeBoundedRelation,
                Map<MoveState, StateValue.Assessment> stateAssessments) {
            this(outcome, witness, events, reachedStates, deadEndStates, metrics, completeBoundedRelation, stateAssessments, null);
        }
        public Result { witness = List.copyOf(witness); events = List.copyOf(events); reachedStates = Set.copyOf(reachedStates); deadEndStates = List.copyOf(deadEndStates); stateAssessments = Map.copyOf(stateAssessments); }
        public boolean reached() { return outcome == Outcome.TARGET_REACHED; }
        public boolean accountingComplete() {
            return stagedIncrementalExecution == null || stagedIncrementalExecution.accountingComplete();
        }
    }
    /** A caller-supplied quality objective, not a desired expression or mathematical authority. */
    public record ObjectiveScore(long value, long work) {
        public ObjectiveScore {
            if (work < 1) throw new IllegalArgumentException("objective inspection must report positive work");
        }
    }
    @FunctionalInterface public interface Objective { ObjectiveScore evaluate(MoveState state); }
    public record QualityResult(Result search, MoveState incumbent, long inputScore, long outputScore,
            List<WitnessStep> witness, long objectiveWork) {
        public QualityResult { witness = List.copyOf(witness); }
    }

    public Result search(Problem problem) { return search(problem, SearchContinuationContract.PATH_SENSITIVE); }
    public Result search(Problem problem,SearchContinuationContract contract) {
        var raw = new MoveSearchKernel<String,MoveState,SearchMove,StateValue.Assessment,MoveVerifier.Verification>()
            .search(new LegacySearchExecution(problem),contract,null);
        return LegacySearchExecution.project(problem,raw);
    }
    public QualityResult searchUntil(Problem problem,Objective objective,long maximumOutputScore,SearchContinuationContract contract) {
        if (!problem.context().goal().isEmpty() || problem.mode()!=Mode.FAST)
            throw new IllegalArgumentException("quality stopping requires source-only FAST search");
        var online = new MoveSearchObjective<MoveState,SearchMove,MoveVerifier.Verification>(objective::evaluate,maximumOutputScore);
        var raw = new MoveSearchKernel<String,MoveState,SearchMove,StateValue.Assessment,MoveVerifier.Verification>()
            .search(new LegacySearchExecution(problem),contract,online);
        return new QualityResult(LegacySearchExecution.project(problem,raw),online.incumbent(),online.inputScore(),online.outputScore(),
            online.witness().stream().map(LegacySearchExecution::step).toList(),online.objectiveWork());
    }
}
