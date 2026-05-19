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

        PriorityQueue<SearchState> frontier = new PriorityQueue<>(Comparator.comparingInt(this::priority));
        List<SearchState> explored = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        frontier.add(rootState);

        while (!frontier.isEmpty() && explored.size() < problem.heuristic().maxVisitedExpressions()) {
            SearchState current = frontier.remove();
            if (!visited.add(stateKey(current))) {
                continue;
            }
            explored.add(current);
            if (current.depth() >= problem.heuristic().maxDepth()) {
                continue;
            }

            int generated = 0;
            for (Transformation transformation : problem.engine().transform(current.expression())) {
                if (generated >= problem.heuristic().maxCandidatesPerState()) {
                    break;
                }
                if (current.appliedRuleApplications().contains(transformation.applicationKey())) {
                    continue;
                }
                int expandedSteps = current.expandedStepCount() + (transformation.kind() == RewriteKind.EXPAND ? 1 : 0);
                if (expandedSteps > problem.heuristic().maxExpandingSteps()) {
                    continue;
                }
                String nextExpression = transformation.transformedExpression();
                String hash = problem.canonicalizer().stableHash(nextExpression);
                if (nextExpression.equals(current.expression())) {
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
                    improvement
                );
                if (visited.contains(stateKey(nextState))) {
                    continue;
                }
                frontier.add(nextState);
                generated++;
            }
        }
        return explored;
    }

    protected int priority(SearchState state) {
        int depthPenalty = state.depth() * 2;
        int expansionPenalty = state.expandedStepCount() * 5;
        int noImprovementPenalty = state.improvement() <= 0 && state.depth() > 0 ? 4 : 0;
        return state.score().weightedTotal() + depthPenalty + expansionPenalty + noImprovementPenalty;
    }

    private String stateKey(SearchState state) {
        return state.canonicalHash() + ":" + state.appliedRuleApplications();
    }
}
