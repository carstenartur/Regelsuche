package de.regelsuche.search.strategy;

import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BeamSearchStrategy implements SearchStrategy {
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
        List<SearchState> beam = List.of(rootState);
        Set<String> visited = new HashSet<>();

        for (int depth = 0; depth <= problem.heuristic().maxDepth() && !beam.isEmpty(); depth++) {
            List<SearchState> next = new ArrayList<>();
            for (SearchState current : beam) {
                if (explored.size() >= problem.heuristic().maxVisitedExpressions()) {
                    return explored;
                }
                if (!visited.add(stateKey(current))) {
                    continue;
                }
                if (current.depth() > 0
                    && TranspositionGate.evaluate(problem.memory(), current,
                        current.canonicalHash() + "#" + current.depth())
                    == TranspositionGate.Verdict.PRUNE) {
                    continue;
                }
                explored.add(current);
                if (current.depth() >= problem.heuristic().maxDepth()) {
                    continue;
                }
                int generated = 0;
                List<Transformation> transformations = new ArrayList<>(problem.engine().transform(current.expression()));
                transformations.sort(Comparator
                    .comparing(Transformation::rule)
                    .thenComparing(Transformation::transformedExpression)
                    .thenComparing(Transformation::applicationKey));
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
                    List<RewriteKind> appliedRuleKinds = new ArrayList<>(current.appliedRuleKinds());
                    appliedRuleKinds.add(transformation.kind());
                    List<Boolean> equivalenceFlags = new ArrayList<>(current.equivalencePreservingFlags());
                    equivalenceFlags.add(transformation.equivalencePreservingByConstruction());
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
                        equivalenceFlags
                    );
                    if (visited.contains(stateKey(nextState))) {
                        continue;
                    }
                    next.add(nextState);
                    generated++;
                }
            }
            next.sort(Comparator
                .comparingInt((SearchState state) -> priority(state, problem))
                .thenComparingInt(SearchState::depth)
                .thenComparing(state -> String.join("->", state.appliedRuleIds()))
                .thenComparing(SearchState::canonicalHash)
                .thenComparing(SearchState::expression)
                .thenComparing(state -> String.join("->", state.path()))
                .thenComparing(state -> String.join("->", sortedValues(state.appliedRuleApplications()))));
            beam = next.stream().limit(problem.heuristic().beamWidth()).toList();
        }
        return explored;
    }

    private int priority(SearchState state) {
        int depthPenalty = state.depth() * 2;
        int expansionPenalty = state.expandedStepCount() * 5;
        int noImprovementPenalty = state.improvement() <= 0 && state.depth() > 0 ? 4 : 0;
        return state.score().weightedTotal() + depthPenalty + expansionPenalty + noImprovementPenalty;
    }

    private int priority(SearchState state, SearchProblem problem) {
        if (problem.costModel() == null) {
            return priority(state);
        }
        int depthPenalty = state.depth() * 2;
        int expansionPenalty = state.expandedStepCount() * 5;
        int noImprovementPenalty = state.improvement() <= 0 && state.depth() > 0 ? 4 : 0;
        int modelCost = problem.costModel().cost(state.expression(), problem.canonicalizer(), state.score());
        if (modelCost == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE / 2;
        }
        return modelCost + depthPenalty + expansionPenalty + noImprovementPenalty;
    }

    private String stateKey(SearchState state) {
        return state.canonicalHash() + ":" + state.appliedRuleApplications();
    }

    private List<String> sortedValues(Set<String> values) {
        return values.stream().sorted().toList();
    }
}
