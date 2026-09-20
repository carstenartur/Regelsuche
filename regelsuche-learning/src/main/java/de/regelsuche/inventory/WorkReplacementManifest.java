package de.regelsuche.inventory;

import static de.regelsuche.inventory.LifecycleWorkAccount.requireText;
import de.regelsuche.search.moves.SearchContinuationContract;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pre-bound comparison semantics; labels do not grant mathematical authority. */
public record WorkReplacementManifest(String baselineCommit, Revisions revisions, String informationRegime,
        Quality quality, List<Long> seeds, Resources resources, Profile profile, ObservationMode observationMode,
        UnsolvedPolicy unsolvedPolicy, List<Partition> partitions) {
    public static final String REVISION = "regelsuche.work-replacement-manifest/v3";
    public enum Arm { B0, B1, L1, L_ORACLE }
    public enum Profile { FRESH_PROCESS_PER_QUERY, LOADED_STREAM, PROVISIONED_MODEL }
    public enum ObservationMode { PROFILE, QUIET_TIMING }
    public enum UnsolvedPolicy { NO_RUNTIME_RATIO, PENALIZE_AT_TIMEOUT }
    public enum Split { TRAIN, VALIDATION, FINAL_TEST }
    public enum QualityMode { SUFFICIENT_QUALITY_MIN_WORK, BEST_QUALITY_FIXED_BUDGET }
    public record Quality(String objectiveDefinition, QualityMode mode, long maximumScore, SearchContinuationContract continuation) {
        public Quality { requireText(objectiveDefinition); Objects.requireNonNull(mode); Objects.requireNonNull(continuation); }
    }
    public record Revisions(String historicalExecution, String improvedExecution, String rules, String model, String checker) {
        public Revisions {
            for (String value : List.of(historicalExecution, improvedExecution, rules, model, checker)) requireText(value);
        }
    }
    public record Binding(String execution, String rules, String model, String checker, ObservationMode observationMode) {}
    /** Remaining budget is divided across remaining queries; unused shares carry forward in fixed order. */
    public record Resources(long totalWork, long queryTimeoutNanos, int maxStates, int maxDepth, String allocator) {
        public Resources {
            if (totalWork < 1 || queryTimeoutNanos < 1 || maxStates < 1 || maxDepth < 0
                    || !"REMAINING_EQUAL_SHARE_WITH_CARRY".equals(allocator)) throw new IllegalArgumentException("unsupported resource contract");
        }
    }
    /** Source identity is structural; family identity is independent of names/coefficients. */
    public record Partition(String id, String sourceIdentity, String family, Split split, List<String> derivedFrom) {
        public Partition {
            requireText(id); requireText(sourceIdentity); requireText(family); Objects.requireNonNull(split);
            derivedFrom = List.copyOf(derivedFrom);
        }
    }
    public WorkReplacementManifest {
        if (baselineCommit == null || !baselineCommit.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("full baseline commit required");
        Objects.requireNonNull(revisions); requireText(informationRegime); Objects.requireNonNull(quality);
        seeds = List.copyOf(seeds); if (seeds.isEmpty()) throw new IllegalArgumentException("seed required");
        Objects.requireNonNull(resources); Objects.requireNonNull(profile); Objects.requireNonNull(observationMode);
        Objects.requireNonNull(unsolvedPolicy); partitions = List.copyOf(partitions);
        validatePartitions(partitions, informationRegime);
    }
    public String toCanonicalJson() {
        return new de.regelsuche.json.JsonWriter().beginObject().property("schema", REVISION)
            .property("contract", de.regelsuche.evolution.LearnedSchedulingArtifacts.json(this)).endObject().toString();
    }
    public Binding binding(Arm arm) {
        return new Binding(arm == Arm.B0 ? revisions.historicalExecution() : revisions.improvedExecution(),
            revisions.rules(), arm == Arm.B0 || arm == Arm.B1 ? "NONE" : revisions.model(), revisions.checker(), observationMode);
    }
    public void validateBinding(Arm arm, Binding actual) {
        if (!binding(arm).equals(actual)) throw new IllegalArgumentException("execution/rule/model/checker/observation binding mismatch: " + arm);
    }
    public void requireTrainingSources(List<String> identities) {
        var allowed = partitions.stream().filter(value -> value.split() == Split.TRAIN).map(Partition::sourceIdentity).toList();
        if (!allowed.containsAll(identities)) throw new IllegalArgumentException("non-TRAIN source reached acquisition or selection");
    }
    private static void validatePartitions(List<Partition> partitions, String information) {
        var ids = new HashMap<String, Partition>(); var sources = new HashMap<String, Split>();
        var families = new HashMap<String, Split>();
        for (var value : partitions) {
            if (ids.put(value.id(), value) != null) throw new IllegalArgumentException("duplicate partition id");
            sameSplit(sources, value.sourceIdentity(), value.split());
            if (information.equals("SEALED_FAMILY_HOLDOUT")) sameSplit(families, value.family(), value.split());
        }
        for (var value : partitions) ancestors(value, ids, new HashSet<>());
    }
    private static void sameSplit(Map<String, Split> seen, String key, Split split) {
        var previous = seen.putIfAbsent(key, split);
        if (previous != null && previous != split) throw new IllegalArgumentException("partition leakage: " + key);
    }
    private static void ancestors(Partition value, Map<String, Partition> ids, java.util.Set<String> path) {
        if (!path.add(value.id())) throw new IllegalArgumentException("cyclic provenance");
        for (var id : value.derivedFrom()) {
            var parent = ids.get(id);
            if (parent == null || parent.split().ordinal() > value.split().ordinal())
                throw new IllegalArgumentException("future partition flowed backwards");
            ancestors(parent, ids, path);
        }
        path.remove(value.id());
    }
}
