package de.regelsuche.search.strategy;

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
    int improvement
) {
    public SearchState {
        path = List.copyOf(path);
        appliedRuleIds = List.copyOf(appliedRuleIds);
        appliedRuleApplications = Set.copyOf(appliedRuleApplications);
    }
}
