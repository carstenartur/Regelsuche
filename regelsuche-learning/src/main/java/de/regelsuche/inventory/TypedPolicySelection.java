package de.regelsuche.inventory;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MovePriorityPolicy;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Bounded TRAIN-only selection of declared ranking profiles using actual search work.
 * This is a small empirical policy selector, not proof authority or a holdout benchmark.
 */
public final class TypedPolicySelection {
    public static final String REVISION = "regelsuche.typed-policy-selection/v2";
    public static final String ACCOUNTING_REVISION = "regelsuche.typed-policy-selection/v3-accounting";
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final HistoryMovePolicy.Weights ZERO = new HistoryMovePolicy.Weights(0, 0, 0, 0, 0, 0, 0, 0);
    public enum PolicyKind { INVENTORY_ORDER, HISTORY_RANKED }

    /** Same selector lifecycle for optimization tasks with no manufactured endpoint. */
    public TypedSourcePolicySelection.Frozen trainSourceOnly(List<TrainingTask> tasks,
            List<TypedSourcePolicySelection.Profile> profiles,
            de.regelsuche.search.moves.TypedSourceOnlySearch.Objective objective) {
        return TypedSourcePolicySelection.train(tasks, profiles, objective);
    }

    public record Profile(String id, PolicyKind kind, HistoryMovePolicy.Weights weights) {
        public Profile(String id, HistoryMovePolicy.Weights weights) { this(id, PolicyKind.HISTORY_RANKED, weights); }
        public Profile {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("profile ID required");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(weights, "weights");
            if (kind == PolicyKind.INVENTORY_ORDER && !ZERO.equals(weights)) {
                throw new IllegalArgumentException("inventory order does not apply ranking weights");
            }
        }
        public static Profile inventoryOrder(String id) { return new Profile(id, PolicyKind.INVENTORY_ORDER, ZERO); }
    }

    public record TrainingTask(String id, TypedMoveSearch.Problem problem) {
        public TrainingTask {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("training task ID required");
            Objects.requireNonNull(problem, "problem");
        }
    }

    public record Observation(String taskId, MoveSearch.Outcome outcome, MoveSearch.Metrics metrics,
            boolean accountingComplete, String executionRevision) {
        public Observation(String taskId, MoveSearch.Outcome outcome, MoveSearch.Metrics metrics) {
            this(taskId, outcome, metrics, true, null);
        }
        public Observation {
            Objects.requireNonNull(taskId); Objects.requireNonNull(outcome); Objects.requireNonNull(metrics);
        }
    }

    public record Trial(Profile profile, List<Observation> observations) {
        public Trial { Objects.requireNonNull(profile); observations = List.copyOf(observations); }
        public long totalWork() {
            return observations.stream().mapToLong(observation -> observation.metrics().totalWork()).reduce(0, Math::addExact);
        }
        public boolean accountingComplete() { return observations.stream().allMatch(Observation::accountingComplete); }
        public boolean explicitAccounting() {
            return observations.stream().anyMatch(o -> o.executionRevision() != null || !o.accountingComplete());
        }
        public long solved() {
            return observations.stream().filter(observation -> observation.outcome() == MoveSearch.Outcome.TARGET_REACHED).count();
        }
    }

    public static final class FrozenPolicy {
        private final RuleHistoryMemory.Snapshot history;
        private final Profile selected;
        private final List<Trial> trials;
        private final List<String> trainingSources;
        private final String json;

        private FrozenPolicy(RuleHistoryMemory.Snapshot history, Profile selected, List<Trial> trials,
                List<String> trainingSources) {
            this.history = history;
            this.selected = selected;
            this.trials = List.copyOf(trials);
            this.trainingSources = List.copyOf(trainingSources);
            this.json = render();
        }
        public RuleHistoryMemory.Snapshot history() { return history; }
        public Profile selected() { return selected; }
        public List<Trial> trials() { return trials; }
        /** Known work from every trial; a total-work claim also requires accountingComplete(). */
        public long trainingWork() { return trials.stream().mapToLong(Trial::totalWork).reduce(0, Math::addExact); }
        public boolean accountingComplete() { return trials.stream().allMatch(Trial::accountingComplete); }
        public String revision() { return trials.stream().anyMatch(Trial::explicitAccounting) ? ACCOUNTING_REVISION : REVISION; }
        public String toCanonicalJson() { return json; }

        /** Exact-source exclusion only; disjoint mathematical families require an external protocol. */
        public TypedMoveSearch.Result evaluate(TypedMoveSearch.Problem problem) {
            if (problem.context().phase() != MoveContext.Phase.FROZEN_EVALUATION) {
                throw new IllegalArgumentException("frozen policy evaluation requires FROZEN_EVALUATION");
            }
            if (trainingSources.contains(CODEC.encodeExpression(problem.source()))) {
                throw new IllegalArgumentException("evaluation source was used during TRAIN");
            }
            return execute(problem, history, selected);
        }

        private String render() {
            String schema = revision();
            boolean explicitAccounting = schema.equals(ACCOUNTING_REVISION);
            var writer = new JsonWriter().beginObject().property("schema", schema)
                .property("authority", "EXPERIMENTAL_NO_PROMOTION")
                .property("selection", "MAX_SOLVED_THEN_MIN_TOTAL_WORK_THEN_DECLARED_ORDER")
                .property("workScope", "CHARGED_SEARCH_MECHANICS_AND_REPLAY;NOT_TOTAL_CPU_OR_FIT_SERIALIZATION")
                .property("selected", selected.id()).property("trainingWork", trainingWork())
                .stringArray("trainingSources", trainingSources)
                .property("history", historyJson(history));
            if (explicitAccounting) writer.property("accountingComplete", accountingComplete())
                .property("accountingScope", "KNOWN_REPORTED_WORK;ACCOUNTING_COMPLETE_REQUIRED_FOR_TOTAL");
            writer.array("trials", values -> trials.forEach(trial -> values.objectValue(value -> writeTrial(value, trial, explicitAccounting))));
            return writer.endObject().toString();
        }
        private void writeTrial(JsonWriter writer, Trial trial, boolean explicitAccounting) {
            writer.property("profile", trial.profile().id()).property("kind", trial.profile().kind().name())
                .property("weights", weightsJson(trial.profile().weights()))
                .property("solved", trial.solved()).property("totalWork", trial.totalWork());
            if (explicitAccounting) writer.property("accountingComplete", trial.accountingComplete());
            writer.array("observations", out -> trial.observations().forEach(o -> out.objectValue(item -> writeObservation(item, o, explicitAccounting))));
        }
        private void writeObservation(JsonWriter writer, Observation o, boolean explicitAccounting) {
            writer.property("task", o.taskId()).property("outcome", o.outcome().name())
                .property("primitiveWork", o.metrics().primitiveWork()).property("searchWork", o.metrics().searchWork())
                .property("verificationWork", o.metrics().verificationWork()).property("totalWork", o.metrics().totalWork());
            if (explicitAccounting) {
                writer.property("accountingComplete", o.accountingComplete());
                if (o.executionRevision() != null) writer.property("executionRevision", o.executionRevision());
            }
        }
    }
    static String executionRevision(TypedMoveSearch.Result result) {
        var receipt = result.encodedResult().stagedIncrementalExecution();
        return receipt == null ? null : receipt.workRevision();
    }

    public FrozenPolicy train(RuleHistoryMemory.Snapshot history, List<TrainingTask> tasks, List<Profile> profiles) {
        Objects.requireNonNull(history, "history");
        tasks = List.copyOf(tasks);
        profiles = List.copyOf(profiles);
        var trainingSources = validate(tasks, profiles);
        var trials = new ArrayList<Trial>();
        for (var profile : profiles) {
            var observations = new ArrayList<Observation>();
            for (var task : tasks) {
                var result = execute(task.problem(), history, profile);
                observations.add(new Observation(task.id(), result.outcome(), result.metrics(),
                    result.accountingComplete(), executionRevision(result)));
            }
            trials.add(new Trial(profile, observations));
        }
        var best = trials.stream().min(Comparator.comparingLong(Trial::solved).reversed()
            .thenComparingLong(Trial::totalWork)).orElseThrow();
        return new FrozenPolicy(history, best.profile(), trials, trainingSources);
    }

    private static List<String> validate(List<TrainingTask> tasks, List<Profile> profiles) {
        if (tasks.size() < 2 || tasks.size() > 32 || profiles.isEmpty() || profiles.size() > 16) {
            throw new IllegalArgumentException("require 2..32 TRAIN tasks and 1..16 profiles");
        }
        var ids = new HashSet<String>();
        var sources = new ArrayList<String>();
        for (var task : tasks) {
            var budget = task.problem().budget();
            String source = CODEC.encodeExpression(task.problem().source());
            if (task.problem().context().phase() != MoveContext.Phase.TRAIN || !ids.add(task.id()) || sources.contains(source)
                    || budget.totalWork() > 1_000_000 || budget.maxStates() > 4096 || budget.maxSearchDepth() > 32) {
                throw new IllegalArgumentException("distinct bounded TRAIN tasks required");
            }
            sources.add(source);
        }
        ids.clear();
        if (profiles.stream().anyMatch(profile -> !ids.add(profile.id()))) {
            throw new IllegalArgumentException("distinct profile IDs required");
        }
        return List.copyOf(sources);
    }

    private static TypedMoveSearch.Result execute(TypedMoveSearch.Problem problem, RuleHistoryMemory.Snapshot history,
            Profile profile) {
        return new TypedMoveSearch().search(new TypedMoveSearch.Problem(problem.source(), problem.context(), problem.providers(),
            profile.kind() == PolicyKind.INVENTORY_ORDER ? MovePriorityPolicy.INVENTORY_ORDER
                : HistoryMovePolicy.typed(history, profile.weights()), problem.verifier(), problem.stateScore(),
            problem.mode(), problem.scheduling(), problem.budget(), problem.stateValue()));
    }

    private static String weightsJson(HistoryMovePolicy.Weights weights) {
        return new JsonWriter().beginObject().property("compression", weights.compression()).property("history", weights.history())
            .property("capability", weights.capability()).property("goal", weights.goal()).property("proof", weights.proof())
            .property("branching", weights.branching()).property("failure", weights.failure()).property("verification", weights.verification())
            .endObject().toString();
    }

    private static String historyJson(RuleHistoryMemory.Snapshot history) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(history);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("cannot freeze history", exception);
        }
    }
}
