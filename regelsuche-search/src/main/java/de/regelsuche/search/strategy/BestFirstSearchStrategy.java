package de.regelsuche.search.strategy;

import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

public class BestFirstSearchStrategy implements SearchStrategy {
    @Override
    public List<SearchState> search(SearchProblem problem) {
        SearchState rootState = createRootState(problem);
        SearchFrame frame = createFrame(problem);
        frame.frontier().add(rootState);
        frame.telemetry().searchStarted(rootState, frame.frontier().size(), frame.visited().size());

        while (shouldContinue(problem, frame)) {
            processNextState(problem, frame);
        }
        frame.telemetry().searchFinished(rootState, frame.frontier().size(), frame.visited().size(), frame.explored().size());
        return frame.explored();
    }

    private SearchFrame createFrame(SearchProblem problem) {
        return new SearchFrame(
            new PriorityQueue<>(priorityComparator(problem)),
            new ArrayList<>(),
            new HashSet<>(),
            SearchTelemetry.forProblem(problem)
        );
    }

    private SearchState createRootState(SearchProblem problem) {
        String root = problem.rootExpression().trim().replaceAll("\\s+", " ");
        ExpressionScore rootScore = problem.scorer().score(root);
        return new SearchState(
            root,
            0,
            rootScore,
            List.of(root),
            List.of(),
            Set.of(),
            0,
            problem.canonicalizer().stableHash(root),
            null,
            null,
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            0
        );
    }

    private Comparator<SearchState> priorityComparator(SearchProblem problem) {
        return Comparator
            .comparingInt((SearchState state) -> priority(state, problem))
            .thenComparingInt(SearchState::depth)
            .thenComparing(SearchState::canonicalHash)
            .thenComparing(SearchState::expression)
            .thenComparing(state -> String.join("->", state.appliedRuleIds()))
            .thenComparing(state -> String.join("->", state.path()))
            .thenComparing(state -> String.join("->", sortedValues(state.appliedRuleApplications())));
    }

    private boolean shouldContinue(SearchProblem problem, SearchFrame frame) {
        return !frame.frontier().isEmpty()
            && frame.explored().size() < problem.heuristic().maxVisitedExpressions();
    }

    private void processNextState(SearchProblem problem, SearchFrame frame) {
        SearchState current = frame.frontier().remove();
        frame.telemetry().stateDequeued(current, frame.frontier().size(), frame.visited().size());
        if (!markVisited(current, frame) || pruneByTransposition(problem, current, frame)) {
            return;
        }
        frame.explored().add(current);
        if (!pruneByDepth(problem, current, frame)) {
            expandState(problem, current, frame);
        }
    }

    private boolean markVisited(SearchState current, SearchFrame frame) {
        if (frame.visited().add(stateKey(current))) {
            frame.telemetry().stateVisited(current, frame.frontier().size(), frame.visited().size());
            return true;
        }
        frame.telemetry().statePrunedDuplicate(current, frame.frontier().size(), frame.visited().size(), 0);
        return false;
    }

    private boolean pruneByTransposition(SearchProblem problem, SearchState current, SearchFrame frame) {
        if (current.depth() == 0 || TranspositionGate.evaluate(problem.memory(), current,
            current.canonicalHash() + "#" + current.depth()) != TranspositionGate.Verdict.PRUNE) {
            return false;
        }
        frame.telemetry().statePrunedTransposition(current, frame.frontier().size(), frame.visited().size());
        return true;
    }

    private boolean pruneByDepth(SearchProblem problem, SearchState current, SearchFrame frame) {
        if (current.depth() < problem.heuristic().maxDepth()) {
            return false;
        }
        frame.telemetry().statePrunedDepth(current, frame.frontier().size(), frame.visited().size());
        return true;
    }

    private void expandState(SearchProblem problem, SearchState current, SearchFrame frame) {
        List<Transformation> transformations = sortedTransformations(problem, current);
        frame.telemetry().stateExpanded(current, frame.frontier().size(), frame.visited().size(), transformations.size());
        int generated = 0;
        for (Transformation transformation : transformations) {
            if (candidateBudgetReached(problem, current, frame, generated)) {
                break;
            }
            if (tryEnqueueTransformation(problem, current, transformation, frame, generated)) {
                generated++;
            }
        }
    }

    private List<Transformation> sortedTransformations(SearchProblem problem, SearchState current) {
        List<Transformation> transformations = new ArrayList<>(problem.engine().transform(current.expression()));
        transformations.sort(Comparator
            .comparing(Transformation::rule)
            .thenComparing(Transformation::transformedExpression)
            .thenComparing(Transformation::applicationKey));
        return transformations;
    }

    private boolean candidateBudgetReached(
        SearchProblem problem,
        SearchState current,
        SearchFrame frame,
        int generated
    ) {
        if (generated < problem.heuristic().maxCandidatesPerState()) {
            return false;
        }
        frame.telemetry().statePrunedBudget(current, frame.frontier().size(), frame.visited().size(), generated);
        return true;
    }

    private boolean tryEnqueueTransformation(
        SearchProblem problem,
        SearchState current,
        Transformation transformation,
        SearchFrame frame,
        int generated
    ) {
        frame.telemetry().transformationGenerated(current, transformation, frame.frontier().size(), frame.visited().size(), generated);
        String skipReason = skipReason(problem, current, transformation);
        if (!skipReason.isBlank()) {
            frame.telemetry().transformationSkipped(current, transformation, frame.frontier().size(), frame.visited().size(), generated,
                skipReason);
            return false;
        }
        SearchState nextState = createNextState(problem, current, transformation);
        if (frame.visited().contains(stateKey(nextState))) {
            frame.telemetry().statePrunedDuplicate(nextState, frame.frontier().size(), frame.visited().size(), generated);
            return false;
        }
        frame.frontier().add(nextState);
        frame.telemetry().stateEnqueued(nextState, frame.frontier().size(), frame.visited().size(), generated + 1);
        return true;
    }

    private String skipReason(SearchProblem problem, SearchState current, Transformation transformation) {
        if (current.appliedRuleApplications().contains(transformation.applicationKey())) {
            return "repeated-rule-application";
        }
        int expandedSteps = expandedSteps(current, transformation);
        if (expandedSteps > problem.heuristic().maxExpandingSteps()) {
            return "max-expanding-steps";
        }
        if (transformation.transformedExpression().equals(current.expression())) {
            return "same-expression";
        }
        return "";
    }

    private SearchState createNextState(SearchProblem problem, SearchState current, Transformation transformation) {
        String nextExpression = transformation.transformedExpression();
        ExpressionScore nextScore = problem.scorer().score(nextExpression);
        int improvement = current.score().weightedTotal() - nextScore.weightedTotal();
        return new SearchState(
            nextExpression,
            current.depth() + 1,
            nextScore,
            pathWith(current.path(), nextExpression),
            appliedRuleIdsWith(current.appliedRuleIds(), transformation.rule()),
            appliedApplicationsWith(current.appliedRuleApplications(), transformation.applicationKey()),
            expandedSteps(current, transformation),
            problem.canonicalizer().stableHash(nextExpression),
            current.expression(),
            transformation.rule(),
            transformation.kind(),
            transformation.mayIncreaseComplexity(),
            transformation.estimatedCostDelta(),
            transformation.equivalencePreservingByConstruction(),
            improvement,
            rewriteKindsWith(current.appliedRuleKinds(), transformation.kind()),
            equivalenceFlagsWith(current.equivalencePreservingFlags(), transformation.equivalencePreservingByConstruction()),
            assumptionsWith(current.assumptions(), transformation.assumptions())
        );
    }

    private int expandedSteps(SearchState current, Transformation transformation) {
        return current.expandedStepCount() + (transformation.kind() == RewriteKind.EXPAND ? 1 : 0);
    }

    private List<String> pathWith(List<String> path, String nextExpression) {
        List<String> nextPath = new ArrayList<>(path);
        nextPath.add(nextExpression);
        return nextPath;
    }

    private List<String> appliedRuleIdsWith(List<String> appliedRuleIds, String ruleId) {
        List<String> nextRuleIds = new ArrayList<>(appliedRuleIds);
        nextRuleIds.add(ruleId);
        return nextRuleIds;
    }

    private Set<String> appliedApplicationsWith(Set<String> applications, String applicationKey) {
        Set<String> nextApplications = new HashSet<>(applications);
        nextApplications.add(applicationKey);
        return nextApplications;
    }

    private List<RewriteKind> rewriteKindsWith(List<RewriteKind> rewriteKinds, RewriteKind rewriteKind) {
        List<RewriteKind> nextKinds = new ArrayList<>(rewriteKinds);
        nextKinds.add(rewriteKind);
        return nextKinds;
    }

    private List<Boolean> equivalenceFlagsWith(List<Boolean> flags, boolean equivalencePreserving) {
        List<Boolean> nextFlags = new ArrayList<>(flags);
        nextFlags.add(equivalencePreserving);
        return nextFlags;
    }

    private List<String> assumptionsWith(List<String> assumptions, List<String> additionalAssumptions) {
        List<String> nextAssumptions = new ArrayList<>(assumptions);
        nextAssumptions.addAll(additionalAssumptions);
        return nextAssumptions;
    }

    protected int priority(SearchState state) {
        int depthPenalty = state.depth() * 2;
        int expansionPenalty = state.expandedStepCount() * 5;
        int noImprovementPenalty = state.improvement() <= 0 && state.depth() > 0 ? 4 : 0;
        return state.score().weightedTotal() + depthPenalty + expansionPenalty + noImprovementPenalty;
    }

    /**
     * Goal-aware priority. When the {@link SearchProblem} carries a
     * {@link de.regelsuche.scoring.cost.CostModel} the model replaces the
     * raw {@code weightedTotal()} term — every other component (depth and
     * expansion penalties, no-improvement penalty) stays the same so
     * existing tuning is preserved.
     */
    protected int priority(SearchState state, SearchProblem problem) {
        if (problem.costModel() == null) {
            return priority(state);
        }
        int depthPenalty = state.depth() * 2;
        int expansionPenalty = state.expandedStepCount() * 5;
        int noImprovementPenalty = state.improvement() <= 0 && state.depth() > 0 ? 4 : 0;
        int modelCost = problem.costModel().cost(state.expression(), problem.canonicalizer(), state.score());
        if (modelCost == Integer.MAX_VALUE) {
            // Unparseable candidate: treat as worst possible without overflow.
            return Integer.MAX_VALUE / 2;
        }
        return modelCost + depthPenalty + expansionPenalty + noImprovementPenalty;
    }

    private String stateKey(SearchState state) {
        return state.canonicalHash() + ":" + String.join(",", sortedValues(state.appliedRuleApplications()));
    }

    private List<String> sortedValues(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private record SearchFrame(
        PriorityQueue<SearchState> frontier,
        List<SearchState> explored,
        Set<String> visited,
        SearchTelemetry telemetry
    ) {
    }
}
