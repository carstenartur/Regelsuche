package de.regelsuche.search.strategy;

import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class RandomMonteCarloSearchStrategy implements SearchStrategy {
    private final Random random;

    public RandomMonteCarloSearchStrategy(long seed) {
        this.random = new Random(seed);
    }

    @Override
    public List<SearchState> search(SearchProblem problem) {
        String root = problem.rootExpression().trim().replaceAll("\\s+", " ");
        SearchState rootState = new SearchState(
            root,
            0,
            problem.scorer().score(root),
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
        List<SearchState> explored = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        List<SearchState> frontier = new ArrayList<>();
        frontier.add(rootState);
        while (!frontier.isEmpty() && explored.size() < problem.heuristic().maxVisitedExpressions()) {
            SearchState current = frontier.remove(random.nextInt(frontier.size()));
            if (!visited.add(stateKey(current))) {
                continue;
            }
            explored.add(current);
            if (current.depth() >= problem.heuristic().maxDepth()) {
                continue;
            }
            List<Transformation> transformations = new ArrayList<>(problem.engine().transform(current.expression()));
            java.util.Collections.shuffle(transformations, random);
            int generated = 0;
            for (Transformation transformation : transformations) {
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
                if (nextExpression.equals(current.expression())) {
                    continue;
                }
                ExpressionScore nextScore = problem.scorer().score(nextExpression);
                int improvement = current.score().weightedTotal() - nextScore.weightedTotal();
                Set<String> applications = new HashSet<>(current.appliedRuleApplications());
                applications.add(transformation.applicationKey());
                List<String> path = new ArrayList<>(current.path());
                path.add(nextExpression);
                List<String> ruleIds = new ArrayList<>(current.appliedRuleIds());
                ruleIds.add(transformation.rule());
                List<RewriteKind> appliedRuleKinds = new ArrayList<>(current.appliedRuleKinds());
                appliedRuleKinds.add(transformation.kind());
                List<Boolean> equivalenceFlags = new ArrayList<>(current.equivalencePreservingFlags());
                equivalenceFlags.add(transformation.equivalencePreservingByConstruction());
                SearchState nextState = new SearchState(
                    nextExpression,
                    current.depth() + 1,
                    nextScore,
                    path,
                    ruleIds,
                    applications,
                    expandedSteps,
                    problem.canonicalizer().stableHash(nextExpression),
                    current.expression(),
                    transformation.rule(),
                    transformation.kind(),
                    transformation.mayIncreaseComplexity(),
                    transformation.estimatedCostDelta(),
                    transformation.equivalencePreservingByConstruction(),
                    improvement,
                    appliedRuleKinds,
                    equivalenceFlags
                );
                if (!visited.contains(stateKey(nextState))) {
                    frontier.add(nextState);
                    generated++;
                }
            }
        }
        return explored;
    }

    private String stateKey(SearchState state) {
        return state.canonicalHash() + ":" + state.appliedRuleApplications();
    }
}
