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
        String root = problem.rootExpression().trim().replaceAll("\\s+", " ");
        ExpressionScore rootScore = problem.scorer().score(root);
        SearchState rootState = new SearchState(
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

        Comparator<SearchState> byPriority = Comparator
            .comparingInt((SearchState state) -> priority(state, problem))
            .thenComparingInt(SearchState::depth)
            .thenComparing(SearchState::canonicalHash)
            .thenComparing(SearchState::expression)
            .thenComparing(state -> String.join("->", state.appliedRuleIds()))
            .thenComparing(state -> String.join("->", state.path()))
            .thenComparing(state -> String.join("->", sortedValues(state.appliedRuleApplications())));
        PriorityQueue<SearchState> frontier = new PriorityQueue<>(byPriority);
        List<SearchState> explored = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        SearchTelemetry telemetry = SearchTelemetry.forProblem(problem);
        frontier.add(rootState);
        telemetry.searchStarted(rootState, frontier.size(), visited.size());

        while (!frontier.isEmpty() && explored.size() < problem.heuristic().maxVisitedExpressions()) {
            SearchState current = frontier.remove();
            telemetry.stateDequeued(current, frontier.size(), visited.size());
            if (!visited.add(stateKey(current))) {
                telemetry.statePrunedDuplicate(current, frontier.size(), visited.size(), 0);
                continue;
            }
            if (current.depth() > 0
                && TranspositionGate.evaluate(problem.memory(), current,
                    current.canonicalHash() + "#" + current.depth())
                == TranspositionGate.Verdict.PRUNE) {
                telemetry.statePrunedTransposition(current, frontier.size(), visited.size());
                continue;
            }
            explored.add(current);
            telemetry.stateVisited(current, frontier.size(), visited.size());
            if (current.depth() >= problem.heuristic().maxDepth()) {
                telemetry.statePrunedDepth(current, frontier.size(), visited.size());
                continue;
            }

            int generated = 0;
            List<Transformation> transformations = new ArrayList<>(problem.engine().transform(current.expression()));
            transformations.sort(Comparator
                .comparing(Transformation::rule)
                .thenComparing(Transformation::transformedExpression)
                .thenComparing(Transformation::applicationKey));
            telemetry.stateExpanded(current, frontier.size(), visited.size(), transformations.size());
            for (Transformation transformation : transformations) {
                if (generated >= problem.heuristic().maxCandidatesPerState()) {
                    telemetry.statePrunedBudget(current, frontier.size(), visited.size(), generated);
                    break;
                }
                telemetry.transformationGenerated(current, transformation, frontier.size(), visited.size(), generated);
                if (current.appliedRuleApplications().contains(transformation.applicationKey())) {
                    telemetry.transformationSkipped(current, transformation, frontier.size(), visited.size(), generated,
                        "repeated-rule-application");
                    continue;
                }
                int expandedSteps = current.expandedStepCount() + (transformation.kind() == RewriteKind.EXPAND ? 1 : 0);
                if (expandedSteps > problem.heuristic().maxExpandingSteps()) {
                    telemetry.transformationSkipped(current, transformation, frontier.size(), visited.size(), generated,
                        "max-expanding-steps");
                    continue;
                }
                String nextExpression = transformation.transformedExpression();
                String hash = problem.canonicalizer().stableHash(nextExpression);
                if (nextExpression.equals(current.expression())) {
                    telemetry.transformationSkipped(current, transformation, frontier.size(), visited.size(), generated,
                        "same-expression");
                    continue;
                }
                ExpressionScore nextScore = problem.scorer().score(nextExpression);
                int improvement = current.score().weightedTotal() - nextScore.weightedTotal();
                Set<String> applied = new HashSet<>(current.appliedRuleApplications());
                applied.add(transformation.applicationKey());
                List<String> path = new ArrayList<>(current.path());
                path.add(nextExpression);
                List<String> appliedRuleIds = new ArrayList<>(current.appliedRuleIds());
                appliedRuleIds.add(transformation.rule());
                List<RewriteKind> appliedRuleKinds = new ArrayList<>(current.appliedRuleKinds());
                appliedRuleKinds.add(transformation.kind());
                List<Boolean> equivalenceFlags = new ArrayList<>(current.equivalencePreservingFlags());
                equivalenceFlags.add(transformation.equivalencePreservingByConstruction());
                List<String> assumptions = new ArrayList<>(current.assumptions());
                assumptions.addAll(transformation.assumptions());
                SearchState nextState = new SearchState(
                    nextExpression,
                    current.depth() + 1,
                    nextScore,
                    path,
                    appliedRuleIds,
                    applied,
                    expandedSteps,
                    hash,
                    current.expression(),
                    transformation.rule(),
                    transformation.kind(),
                    transformation.mayIncreaseComplexity(),
                    transformation.estimatedCostDelta(),
                    transformation.equivalencePreservingByConstruction(),
                    improvement,
                    appliedRuleKinds,
                    equivalenceFlags,
                    assumptions
                );
                if (visited.contains(stateKey(nextState))) {
                    telemetry.statePrunedDuplicate(nextState, frontier.size(), visited.size(), generated);
                    continue;
                }
                frontier.add(nextState);
                generated++;
                telemetry.stateEnqueued(nextState, frontier.size(), visited.size(), generated);
            }
        }
        telemetry.searchFinished(rootState, frontier.size(), visited.size(), explored.size());
        return explored;
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
}
