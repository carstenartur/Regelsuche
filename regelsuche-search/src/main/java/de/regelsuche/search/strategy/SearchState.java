package de.regelsuche.search.strategy;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.RecordedExecution;
import de.regelsuche.transform.ExecutionWork;
import java.util.ArrayList;
import java.util.Optional;
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
    List<String> assumptions,
    List<Transformation> transformations
) {
    public SearchState {
        path = List.copyOf(path);
        appliedRuleIds = List.copyOf(appliedRuleIds);
        appliedRuleApplications = Set.copyOf(appliedRuleApplications);
        appliedRuleKinds = appliedRuleKinds == null ? List.of() : List.copyOf(appliedRuleKinds);
        equivalencePreservingFlags = equivalencePreservingFlags == null ? List.of() : List.copyOf(equivalencePreservingFlags);
        assumptions = AssumptionSignature.ofExpressions(assumptions).normalizedAssumptions();
        if (transformations == null && depth == 0 && path.equals(List.of(expression))
                && appliedRuleIds.isEmpty() && appliedRuleApplications.isEmpty()
                && appliedRuleKinds.isEmpty() && equivalencePreservingFlags.isEmpty() && expandedStepCount == 0) {
            transformations = List.of();
        }
        if (transformations != null) {
            transformations = List.copyOf(transformations);
            requireRetainedPath(expression, depth, path, appliedRuleIds, appliedRuleApplications, assumptions, transformations);
            if (!transformations.isEmpty()) {
                var incoming = transformations.getLast();
                if (!path.get(path.size() - 2).equals(parentExpression) || !incoming.rule().equals(appliedRuleId)
                        || incoming.kind() != appliedRuleKind || incoming.mayIncreaseComplexity() != mayIncreaseComplexity
                        || incoming.estimatedCostDelta() != estimatedCostDelta
                        || incoming.equivalencePreservingByConstruction() != equivalencePreservingByConstruction
                        || !transformations.stream().map(Transformation::kind).toList().equals(appliedRuleKinds)
                        || !transformations.stream().map(Transformation::equivalencePreservingByConstruction).toList().equals(equivalencePreservingFlags)
                        || transformations.stream().filter(step -> step.kind() == RewriteKind.EXPAND).count() != expandedStepCount) {
                    throw new IllegalArgumentException("state metadata differs from retained transformations");
                }
            }
        }
    }

    /** Observational path descriptions (including e-graph extraction) may lack executable lineage. */
    public SearchState(String expression, int depth, ExpressionScore score, List<String> path,
            List<String> appliedRuleIds, Set<String> appliedRuleApplications, int expandedStepCount,
            String canonicalHash, String parentExpression, String appliedRuleId, RewriteKind appliedRuleKind,
            boolean mayIncreaseComplexity, int estimatedCostDelta, boolean equivalencePreservingByConstruction,
            int improvement, List<RewriteKind> appliedRuleKinds, List<Boolean> equivalencePreservingFlags,
            List<String> assumptions) {
        this(expression, depth, score, path, appliedRuleIds, appliedRuleApplications, expandedStepCount,
            canonicalHash, parentExpression, appliedRuleId, appliedRuleKind, mayIncreaseComplexity,
            estimatedCostDelta, equivalencePreservingByConstruction, improvement, appliedRuleKinds,
            equivalencePreservingFlags, assumptions, null);
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
            equivalencePreservingFlags, newAssumptions, transformations
        );
    }

    /** No serialization or hashing is performed on the search hot path. */
    public Optional<RecordedExecution> recordedExecution() {
        return transformations == null ? Optional.empty()
            : Optional.of(RecordedExecution.capture(path.getFirst(), transformations));
    }

    public Optional<RecordedExecution> incomingExecution() {
        return transformations == null || transformations.isEmpty() ? Optional.empty()
            : Optional.of(RecordedExecution.capture(path.get(path.size() - 2), List.of(transformations.getLast())));
    }

    /** Missing lineage is unknown work, never zero-cost execution. */
    public Optional<ExecutionWork> executionWork() {
        return transformations == null ? Optional.empty() : Optional.of(transformations.stream()
            .map(Transformation::executionWork).reduce(ExecutionWork.ZERO, ExecutionWork::plus));
    }

    public static List<Transformation> extendedPath(SearchState parent, Transformation step) {
        step.provenance().requireSource(parent.expression());
        if (parent.transformations == null) {
            if (step.exactTheoryStepCount() > 0) throw new IllegalArgumentException("theory path cannot extend missing lineage");
            return null;
        }
        var result = new ArrayList<>(parent.transformations);
        result.add(step);
        return List.copyOf(result);
    }

    public static List<String> accumulatedAssumptions(SearchState parent, Transformation step) {
        var result = new ArrayList<>(parent.assumptions());
        result.addAll(step.assumptions());
        return AssumptionSignature.ofExpressions(result).normalizedAssumptions();
    }

    private static void requireRetainedPath(String expression, int depth, List<String> path,
            List<String> rules, Set<String> applications, List<String> assumptions, List<Transformation> steps) {
        if (steps.size() != depth || path.size() != depth + 1 || !path.getLast().equals(expression)
                || !steps.stream().map(Transformation::rule).toList().equals(rules)
                || !Set.copyOf(steps.stream().map(Transformation::applicationKey).toList()).equals(applications)
                || !assumptions.containsAll(AssumptionSignature.ofExpressions(steps.stream()
                    .flatMap(step -> step.assumptions().stream()).toList()).normalizedAssumptions())) {
            throw new IllegalArgumentException("search state differs from its retained execution path");
        }
        for (int i = 0; i < steps.size(); i++) {
            steps.get(i).provenance().requireSource(path.get(i));
            if (!steps.get(i).transformedExpression().equals(path.get(i + 1))) {
                throw new IllegalArgumentException("discontinuous retained search path");
            }
        }
    }
}
