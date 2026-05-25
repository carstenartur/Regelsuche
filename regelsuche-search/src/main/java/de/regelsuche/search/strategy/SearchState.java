package de.regelsuche.search.strategy;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import java.util.Set;

public record SearchState(
    String expression,
    int depth,
    ExpressionScore score,
    List<String> path,
    List<String> appliedRuleIds,
    Set<String> appliedRuleApplications,
    int expandedStepCount,
    String canonicalHash,
    String parentExpression,
    String appliedRuleId,
    RewriteKind appliedRuleKind,
    boolean mayIncreaseComplexity,
    int estimatedCostDelta,
    boolean equivalencePreservingByConstruction,
    int improvement,
    List<RewriteKind> appliedRuleKinds,
    List<Boolean> equivalencePreservingFlags,
    List<String> assumptions
) {
    public SearchState {
        path = List.copyOf(path);
        appliedRuleIds = List.copyOf(appliedRuleIds);
        appliedRuleApplications = Set.copyOf(appliedRuleApplications);
        appliedRuleKinds = appliedRuleKinds == null ? List.of() : List.copyOf(appliedRuleKinds);
        equivalencePreservingFlags = equivalencePreservingFlags == null ? List.of() : List.copyOf(equivalencePreservingFlags);
        assumptions = AssumptionSignature.ofExpressions(assumptions).normalizedAssumptions();
    }

    public SearchState(
        String expression,
        int depth,
        ExpressionScore score,
        List<String> path,
        List<String> appliedRuleIds,
        Set<String> appliedRuleApplications,
        int expandedStepCount,
        String canonicalHash,
        String parentExpression,
        String appliedRuleId,
        RewriteKind appliedRuleKind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreservingByConstruction,
        int improvement,
        List<RewriteKind> appliedRuleKinds,
        List<Boolean> equivalencePreservingFlags
    ) {
        this(
            expression, depth, score, path, appliedRuleIds, appliedRuleApplications,
            expandedStepCount, canonicalHash, parentExpression, appliedRuleId,
            appliedRuleKind, mayIncreaseComplexity, estimatedCostDelta,
            equivalencePreservingByConstruction, improvement, appliedRuleKinds,
            equivalencePreservingFlags, List.of()
        );
    }

    public SearchState(
        String expression,
        int depth,
        ExpressionScore score,
        List<String> path,
        List<String> appliedRuleIds,
        Set<String> appliedRuleApplications,
        int expandedStepCount,
        String canonicalHash,
        String parentExpression,
        String appliedRuleId,
        RewriteKind appliedRuleKind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreservingByConstruction,
        int improvement
    ) {
        this(
            expression, depth, score, path, appliedRuleIds, appliedRuleApplications,
            expandedStepCount, canonicalHash, parentExpression, appliedRuleId,
            appliedRuleKind, mayIncreaseComplexity, estimatedCostDelta,
            equivalencePreservingByConstruction, improvement,
            List.of(), List.of(), List.of()
        );
    }

    public String assumptionFingerprint() {
        return AssumptionSignature.ofExpressions(assumptions).fingerprint();
    }

    public SearchState withAssumptions(List<String> newAssumptions) {
        return new SearchState(
            expression, depth, score, path, appliedRuleIds, appliedRuleApplications,
            expandedStepCount, canonicalHash, parentExpression, appliedRuleId,
            appliedRuleKind, mayIncreaseComplexity, estimatedCostDelta,
            equivalencePreservingByConstruction, improvement, appliedRuleKinds,
            equivalencePreservingFlags, newAssumptions
        );
    }
}
