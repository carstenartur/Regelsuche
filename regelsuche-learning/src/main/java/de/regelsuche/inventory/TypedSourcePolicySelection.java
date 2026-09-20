package de.regelsuche.inventory;

import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.json.JsonWriter;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Source-only continuation utility integrated with {@link TypedPolicySelection}. */
public final class TypedSourcePolicySelection {
    public static final String REVISION = "regelsuche.typed-source-policy-selection/v1";
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private TypedSourcePolicySelection() {}
    public record Profile(String id, List<MoveProvider> providers, MovePriorityPolicy policy) {
        public Profile {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("profile ID required");
            providers = List.copyOf(providers);
            Objects.requireNonNull(policy, "policy");
            if (providers.stream().anyMatch(provider -> !(provider instanceof TypedMoveSearch.TypedProvider)))
                throw new IllegalArgumentException("typed providers required");
        }
    }
    public record Observation(String taskId, long inputScore, long outputScore, long totalWork,
            boolean withinBudget, MoveSearch.Outcome outcome, long wallNanos, long cpuNanos, long allocatedBytes) {}
    public record Trial(Profile profile, List<Observation> observations) {
        public Trial { observations = List.copyOf(observations); }
        public long totalWork() { return observations.stream().mapToLong(Observation::totalWork).reduce(0, Math::addExact); }
        public long violations() { return observations.stream().filter(o -> !o.withinBudget()).count(); }
        public long outputCost() { return observations.stream().mapToLong(Observation::outputScore).reduce(0, Math::addExact); }
    }
    public record Frozen(Profile selected, List<Trial> trials, List<String> trainingSources) {
        public Frozen { trials = List.copyOf(trials); trainingSources = List.copyOf(trainingSources); }
        public long trainingWork() { return trials.stream().mapToLong(Trial::totalWork).reduce(0, Math::addExact); }
        public TypedSourceOnlySearch.Result evaluate(TypedMoveSearch.Problem problem, TypedSourceOnlySearch.Objective objective) {
            if (!problem.context().sourceOnly() || problem.mode() != MoveSearch.Mode.FAST
                    || problem.context().phase() != MoveContext.Phase.FROZEN_EVALUATION
                    || trainingSources.contains(CODEC.encodeExpression(problem.source())))
                throw new IllegalArgumentException("distinct frozen source-only evaluation required");
            return execute(problem, selected, objective);
        }
        public String toCanonicalJson() {
            return new JsonWriter().beginObject().property("schema", REVISION)
                .property("selected", selected.id()).property("trainingWork", trainingWork())
                .property("selection", "MIN_BUDGET_VIOLATIONS_THEN_OUTPUT_COST_THEN_FULL_CONTINUATION_WORK")
                .property("authority", "EMPIRICAL_SCHEDULING_ONLY;NO_MATHEMATICAL_PRUNING")
                .property("mode", "BUDGETED_HEURISTIC;COMPLETE_REFERENCE_UNCHANGED")
                .property("measurement", "WALL_AND_PROCESS_CPU;REQUEST_THREAD_ALLOCATIONS;LOGICAL_WORK_IS_NOT_TIME")
                .stringArray("trainingSources", trainingSources)
                .array("trials", out -> trials.forEach(trial -> out.objectValue(item -> item
                    .property("profile", trial.profile().id()).property("work", trial.totalWork())
                    .property("outputCost", trial.outputCost()).property("budgetViolations", trial.violations())
                    .array("observations", observations -> trial.observations().forEach(o -> observations.objectValue(v -> v
                        .property("task", o.taskId()).property("inputScore", o.inputScore()).property("outputScore", o.outputScore())
                        .property("totalWork", o.totalWork()).property("withinBudget", o.withinBudget()).property("outcome", o.outcome().name())
                        .property("wallNanos", o.wallNanos()).property("cpuNanos", o.cpuNanos()).property("allocatedBytes", o.allocatedBytes())))))))
                .endObject().toString();
        }
    }
    static Frozen train(List<TypedPolicySelection.TrainingTask> tasks, List<Profile> profiles,
            TypedSourceOnlySearch.Objective objective) {
        tasks = List.copyOf(tasks); profiles = List.copyOf(profiles);
        Objects.requireNonNull(objective, "objective");
        if (tasks.size() < 2 || tasks.size() > 32 || profiles.isEmpty() || profiles.size() > 16)
            throw new IllegalArgumentException("require 2..32 selection tasks and 1..16 profiles");
        var sources = new ArrayList<String>();
        var ids = new HashSet<String>();
        for (var task : tasks) {
            var p = task.problem();
            String source = CODEC.encodeExpression(p.source());
            if (!p.context().sourceOnly() || p.mode() != MoveSearch.Mode.FAST
                    || p.context().phase() != MoveContext.Phase.TRAIN || !ids.add(task.id())
                    || sources.contains(source) || p.budget().totalWork() > 1_000_000
                    || p.budget().maxStates() > 4096 || p.budget().maxSearchDepth() > 32)
                throw new IllegalArgumentException("distinct bounded source-only TRAIN tasks required");
            sources.add(source);
        }
        ids.clear();
        if (profiles.stream().anyMatch(profile -> !ids.add(profile.id())))
            throw new IllegalArgumentException("duplicate source policy profile");
        var trials = new ArrayList<Trial>();
        for (var profile : profiles) {
            var observations = new ArrayList<Observation>();
            for (var task : tasks) {
                long start = System.nanoTime(), cpu = cpu(), allocation = allocated();
                var result = execute(task.problem(), profile, objective);
                observations.add(new Observation(task.id(), result.inputScore(), result.outputScore(), result.totalWork(),
                    result.withinBudget(), result.search().outcome(), System.nanoTime() - start,
                    delta(cpu, cpu()), delta(allocation, allocated())));
            }
            trials.add(new Trial(profile, observations));
        }
        var selected = trials.stream().min(Comparator.comparingLong(Trial::violations)
            .thenComparingLong(Trial::outputCost).thenComparingLong(Trial::totalWork)).orElseThrow().profile();
        return new Frozen(selected, trials, sources);
    }
    /** Compile-time seam for the RED quality-control regression; not merge-qualified. */
    public static Frozen trainUntil(List<TypedPolicySelection.TrainingTask> tasks, List<Profile> profiles,
            TypedSourceOnlySearch.Objective objective, long maximumOutputScore, SearchContinuationContract contract) {
        Objects.requireNonNull(contract, "contract");
        return train(tasks, profiles, objective);
    }
    private static TypedSourceOnlySearch.Result execute(TypedMoveSearch.Problem p, Profile profile,
            TypedSourceOnlySearch.Objective objective) {
        return new TypedSourceOnlySearch().search(new TypedMoveSearch.Problem(p.source(), p.context(), profile.providers(),
            profile.policy(), p.verifier(), p.stateScore(), p.mode(), p.scheduling(), p.budget(), p.stateValue()), objective);
    }
    private static long cpu() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean
            ? bean.getProcessCpuTime() : -1;
    }
    private static long allocated() {
        return ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled()
            ? bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
    }
    private static long delta(long before, long after) { return before < 0 || after < 0 ? -1 : Math.max(0, after - before); }
}
