package de.regelsuche.search.learning;

import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import java.util.Objects;

/** Stores successful choices and failed alternatives without affecting live search. */
public interface SearchExperienceRepository {
    void store(SearchExperience experience);

    default void store(SearchTrajectoryRun run) {
        for (SearchTrajectoryRecord record : run.records()) {
            if (!record.decision() || record.parent() == null || record.ruleId().isBlank()) {
                continue;
            }
            store(SearchExperience.from(record));
        }
    }

    List<SearchExperience> findByShape(String family, String parentAlphaShapeHash, int limit);

    ExperienceSummary summary();

    record SearchExperience(
        String experienceId,
        String runId,
        String family,
        String parentValueHash,
        String parentAlphaShapeHash,
        String childValueHash,
        String childAlphaShapeHash,
        String targetAlphaShapeHash,
        String ruleId,
        RewriteKind rewriteKind,
        List<String> assumptions,
        int depth,
        int parentScore,
        int childScore,
        int scoreDelta,
        boolean selectedPath,
        boolean eventualSuccess,
        GoalStatus terminalStatus,
        String pruningReason
    ) {
        public SearchExperience {
            experienceId = safe(experienceId);
            runId = safe(runId);
            family = safe(family);
            parentValueHash = safe(parentValueHash);
            parentAlphaShapeHash = safe(parentAlphaShapeHash);
            childValueHash = safe(childValueHash);
            childAlphaShapeHash = safe(childAlphaShapeHash);
            targetAlphaShapeHash = safe(targetAlphaShapeHash);
            ruleId = safe(ruleId);
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            pruningReason = safe(pruningReason);
            Objects.requireNonNull(terminalStatus, "terminalStatus");
        }

        static SearchExperience from(SearchTrajectoryRecord record) {
            String parentValue = record.parent() == null ? "" : record.parent().valueHash();
            String parentAlpha = record.parent() == null ? "" : record.parent().alphaShapeHash();
            String targetAlpha = record.target() == null ? "" : record.target().alphaShapeHash();
            String id = record.runId() + ":" + record.sequence();
            return new SearchExperience(
                id,
                record.runId(),
                record.family(),
                parentValue,
                parentAlpha,
                record.expression().valueHash(),
                record.expression().alphaShapeHash(),
                targetAlpha,
                record.ruleId(),
                record.rewriteKind(),
                record.assumptions(),
                record.depth(),
                record.parentScore(),
                record.score(),
                record.score() - record.parentScore(),
                record.selectedPath(),
                record.eventualSuccess(),
                record.terminalStatus(),
                record.pruningReason());
        }

        public boolean successfulChoice() {
            return selectedPath && eventualSuccess;
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }

    record ExperienceSummary(
        int total,
        int successfulChoices,
        int failedAlternatives,
        int families,
        int structuralShapes
    ) {
    }
}
