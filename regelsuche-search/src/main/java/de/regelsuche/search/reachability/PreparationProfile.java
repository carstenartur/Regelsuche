package de.regelsuche.search.reachability;

import de.regelsuche.transform.Transformation;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Cumulative, opt-in profiles for whole-source principal preparation. */
public enum PreparationProfile {
    DIRECT_ONLY,
    THEORY_MATCHING,
    SAFE_EXACT_PREPARATION,
    SAFE_PREPARATION_PLUS_LOCAL_BRIDGE;

    public record Result(String ruleId, String ruleFingerprint, String status, String selectedStage, String detail,
            Optional<Transformation> candidate, String certificateHash, List<Stage> stages) {
        public Result {
            stages = List.copyOf(stages);
        }
    }

    /** Counts returned matcher work and real dispatches. Bridge work exists only when the bridge returned. */
    public record Stage(String name, String detail, int matcherSteps, int matcherBranches, int structuralComparisons,
            int executorReplays, int specialistDispatches, int specialistResults,
            PatternTargetedLocalBridgeSearch.Work bridgeWork, Set<String> reachedLimits) {
        public Stage {
            reachedLimits = Set.copyOf(reachedLimits);
        }
    }
}
