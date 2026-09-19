package de.regelsuche.inventory;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.search.moves.MoveContext;
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
    public static final String REVISION = "regelsuche.typed-policy-selection/v1";
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();

    public record Profile(String id, HistoryMovePolicy.Weights weights) {
        public Profile {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("profile ID required");
            Objects.requireNonNull(weights, "weights");
        }
    }

    public record TrainingTask(String id, TypedMoveSearch.Problem problem) {
        public TrainingTask {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("training task ID required");
            Objects.requireNonNull(problem, "problem");
        }
    }

    public record Observation(String taskId, MoveSearch.Outcome outcome, MoveSearch.Metrics metrics) {
        public Observation {
            Objects.requireNonNull(taskId); Objects.requireNonNull(outcome); Objects.requireNonNull(metrics);
        }
    }

    public record Trial(Profile profile, List<Observation> observations) {
        public Trial { Objects.requireNonNull(profile); observations = List.copyOf(observations); }
        public long totalWork() {
            return observations.stream().mapToLong(observation -> observation.metrics().totalWork()).reduce(0, Math::addExact);
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
        /** Includes every trial, including unsuccessful or work-exhausted searches. */
        public long trainingWork() { return trials.stream().mapToLong(Trial::totalWork).reduce(0, Math::addExact); }
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
            var writer = new JsonWriter().beginObject().property("schema", REVISION)
                .property("authority", "EXPERIMENTAL_NO_PROMOTION")
                .property("selection", "MAX_SOLVED_THEN_MIN_TOTAL_WORK_THEN_DECLARED_ORDER")
                .property("workScope", "CHARGED_SEARCH_MECHANICS_AND_REPLAY;NOT_TOTAL_CPU_OR_FIT_SERIALIZATION")
                .property("selected", selected.id()).property("trainingWork", trainingWork())
                .stringArray("trainingSources", trainingSources)
                .property("history", historyJson(history))
                .array("trials", values -> trials.forEach(trial -> values.objectValue(value -> value
                    .property("profile", trial.profile().id()).property("weights", weightsJson(trial.profile().weights()))
                    .property("solved", trial.solved()).property("totalWork", trial.totalWork())
                    .array("observations", observations -> trial.observations().forEach(observation -> observations.objectValue(item -> item
                        .property("task", observation.taskId()).property("outcome", observation.outcome().name())
                        .property("primitiveWork", observation.metrics().primitiveWork())
                        .property("searchWork", observation.metrics().searchWork())
                        .property("verificationWork", observation.metrics().verificationWork())
                        .property("totalWork", observation.metrics().totalWork())))))));
            return writer.endObject().toString();
        }
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
                observations.add(new Observation(task.id(), result.outcome(), result.metrics()));
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
            HistoryMovePolicy.typed(history, profile.weights()), problem.verifier(), problem.stateScore(),
            problem.mode(), problem.scheduling(), problem.budget()));
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
