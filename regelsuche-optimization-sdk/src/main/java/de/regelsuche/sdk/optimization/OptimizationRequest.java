package de.regelsuche.sdk.optimization;

import de.regelsuche.search.program.JointComputationPlan;
import java.util.*;

public record OptimizationRequest(JointComputationPlan plan, SourceEvaluationTrace sourceTrace,
        Set<NumericKind> selectedKinds, String semanticsRevision, Set<SemanticAssumption> assumptions,
        SafetyProfile safetyProfile, OptimizationGoal goal, OptimizationBudget budget, CheckedPolicy checkedPolicy) {
    public OptimizationRequest {
        Objects.requireNonNull(plan); Objects.requireNonNull(sourceTrace); selectedKinds = Set.copyOf(selectedKinds);
        Objects.requireNonNull(semanticsRevision); assumptions = Set.copyOf(assumptions);
        Objects.requireNonNull(safetyProfile); Objects.requireNonNull(goal); Objects.requireNonNull(budget); Objects.requireNonNull(checkedPolicy);
        if (selectedKinds.isEmpty()) throw new IllegalArgumentException("NUMERIC_KIND_SELECTION_REQUIRED");
        if (safetyProfile != SafetyProfile.CHECKED_THROW && !checkedPolicy.equals(CheckedPolicy.NONE))
            throw new IllegalArgumentException("CHECKED_POLICY_REQUIRES_EXPLICIT_PROFILE");
    }
}
