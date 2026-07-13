package de.regelsuche.search.learning;

import de.regelsuche.search.learning.SearchTrajectoryContext.DatasetSplit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Assigns whole mathematical families and rejects exact/alpha leakage across splits. */
public final class TrajectorySplitPlanner {
    public SplitPlan assignByFamily(List<SearchTrajectoryRun> runs) {
        Objects.requireNonNull(runs, "runs");
        List<String> families = runs.stream()
            .map(run -> run.context().family())
            .distinct()
            .sorted()
            .toList();
        Map<String, DatasetSplit> assignments = assignments(families);
        List<SearchTrajectoryRun> assigned = runs.stream()
            .map(run -> run.withSplit(assignments.get(run.context().family())))
            .sorted(Comparator.comparing(run -> run.context().runId()))
            .toList();
        return new SplitPlan(assigned, leakageViolations(assigned));
    }

    public List<LeakageViolation> leakageViolations(List<SearchTrajectoryRun> runs) {
        List<LeakageViolation> violations = new ArrayList<>();
        collectViolations(runs, true, violations);
        collectViolations(runs, false, violations);
        return List.copyOf(violations);
    }

    private static Map<String, DatasetSplit> assignments(List<String> families) {
        Map<String, DatasetSplit> assignments = new LinkedHashMap<>();
        if (families.isEmpty()) {
            return Map.of();
        }
        if (families.size() == 1) {
            assignments.put(families.getFirst(), DatasetSplit.TRAIN);
            return Map.copyOf(assignments);
        }
        if (families.size() == 2) {
            assignments.put(families.getFirst(), DatasetSplit.TRAIN);
            assignments.put(families.getLast(), DatasetSplit.TEST);
            return Map.copyOf(assignments);
        }
        for (int index = 0; index < families.size(); index++) {
            DatasetSplit split = index == families.size() - 1
                ? DatasetSplit.TEST
                : index == families.size() - 2
                    ? DatasetSplit.VALIDATION
                    : DatasetSplit.TRAIN;
            assignments.put(families.get(index), split);
        }
        return Map.copyOf(assignments);
    }

    private static void collectViolations(
        List<SearchTrajectoryRun> runs,
        boolean exact,
        List<LeakageViolation> violations
    ) {
        Map<String, Map<DatasetSplit, Set<String>>> owners = new LinkedHashMap<>();
        for (SearchTrajectoryRun run : runs) {
            String fingerprint = exact
                ? run.taskValueFingerprint()
                : run.taskAlphaFingerprint();
            owners.computeIfAbsent(fingerprint, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(run.context().split(), ignored -> new LinkedHashSet<>())
                .add(run.context().runId());
        }
        owners.forEach((fingerprint, splitOwners) -> {
            Set<DatasetSplit> materialSplits = new LinkedHashSet<>(splitOwners.keySet());
            materialSplits.remove(DatasetSplit.UNASSIGNED);
            if (materialSplits.size() > 1) {
                List<String> runIds = splitOwners.values().stream()
                    .flatMap(Set::stream)
                    .sorted()
                    .toList();
                violations.add(new LeakageViolation(
                    exact ? "EXACT_TASK" : "ALPHA_TASK",
                    fingerprint,
                    materialSplits.stream().sorted().toList(),
                    runIds));
            }
        });
    }

    public record LeakageViolation(
        String kind,
        String fingerprint,
        List<DatasetSplit> splits,
        List<String> runIds
    ) {
        public LeakageViolation {
            splits = List.copyOf(splits);
            runIds = List.copyOf(runIds);
        }
    }

    public record SplitPlan(
        List<SearchTrajectoryRun> runs,
        List<LeakageViolation> leakageViolations
    ) {
        public SplitPlan {
            runs = List.copyOf(runs);
            leakageViolations = List.copyOf(leakageViolations);
        }

        public boolean passed() {
            return leakageViolations.isEmpty();
        }

        public Map<DatasetSplit, Long> runCounts() {
            Map<DatasetSplit, Long> counts = new LinkedHashMap<>();
            for (DatasetSplit split : DatasetSplit.values()) {
                counts.put(split, runs.stream()
                    .filter(run -> run.context().split() == split)
                    .count());
            }
            return Map.copyOf(counts);
        }
    }
}
