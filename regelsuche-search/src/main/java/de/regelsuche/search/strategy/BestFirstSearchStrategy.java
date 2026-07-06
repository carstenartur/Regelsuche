package de.regelsuche.search.strategy;

import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchEventType;
import de.regelsuche.search.telemetry.SearchObserver;
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
        frontier.add(rootState);

        SearchObserver observer = problem.observer();
        long[] sequence = {0L};
        emit(observer, sequence, SearchEventType.SEARCH_STARTED, rootState, problem,
            frontier.size(), visited.size(), 0, "");

        while (!frontier.isEmpty() && explored.size() < problem.heuristic().maxVisitedExpressions()) {
            SearchState current = frontier.remove();
            emit(observer, sequence, SearchEventType.STATE_DEQUEUED, current, problem,
                frontier.size(), visited.size(), 0, "");
            if (!visited.add(stateKey(current))) {
                emit(observer, sequence, SearchEventType.STATE_PRUNED_DUPLICATE, current, problem,
                    frontier.size(), visited.size(), 0, "visited-state-key");
                continue;
            }
            if (current.depth() > 0
                && TranspositionGate.evaluate(problem.memory(), current,
                    current.canonicalHash() + "#" + current.depth())
                == TranspositionGate.Verdict.PRUNE) {
                emit(observer, sequence, SearchEventType.STATE_PRUNED_TRANSPOSITION, current, problem,
                    frontier.size(), visited.size(), 0, "transposition-gate");
                continue;
            }
            explored.add(current);
            emit(observer, sequence, SearchEventType.STATE_VISITED, current, problem,
                frontier.size(), visited.size(), 0, "");
            if (current.depth() >= problem.heuristic().maxDepth()) {
                emit(observer, sequence, SearchEventType.STATE_PRUNED_DEPTH, current, problem,
                    frontier.size(), visited.size(), 0, "max-depth");
                continue;
            }

            int generated = 0;
            List<Transformation> transformations = new ArrayList<>(problem.engine().transform(current.expression()));
            transformations.sort(Comparator
                .comparing(Transformation::rule)
                .thenComparing(Transformation::transformedExpression)
                .thenComparing(Transformation::applicationKey));
            emit(observer, sequence, SearchEventType.STATE_EXPANDED, current, problem,
                frontier.size(), visited.size(), transformations.size(), "");
            for (Transformation transformation : transformations) {
                emitTransformation(observer, sequence, current, transformation, problem,
                    frontier.size(), visited.size(), generated, "");
                if (generated >= problem.heuristic().maxCandidatesPerState()) {
                    emitTransformation(observer, sequence, current, transformation, problem,
                        frontier.size(), visited.size(), generated, "max-candidates-per-state");
                    break;
                }
                if (current.appliedRuleApplications().contains(transformation.applicationKey())) {
                    emitTransformation(observer, sequence, current, transformation, problem,
                        frontier.size(), visited.size(), generated, "repeated-rule-application");
                    continue;
                }
                int expandedSteps = current.expandedStepCount() + (transformation.kind() == RewriteKind.EXPAND ? 1 : 0);
                if (expandedSteps > problem.heuristic().maxExpandingSteps()) {
                    emitTransformation(observer, sequence, current, transformation, problem,
                        frontier.size(), visited.size(), generated, "max-expanding-steps");
                    continue;
                }
                String nextExpression = transformation.transformedExpression();
                String hash = problem.canonicalizer().stableHash(nextExpression);
                if (nextExpression.equals(current.expression())) {
                    emitTransformation(observer, sequence, current, transformation, problem,
                        frontier.size(), visited.size(), generated, "same-expression");
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
                    emit(observer, sequence, SearchEventType.STATE_PRUNED_DUPLICATE, nextState, problem,
                        frontier.size(), visited.size(), generated, "visited-state-key");
                    continue;
                }
                frontier.add(nextState);
                generated++;
                emit(observer, sequence, SearchEventType.STATE_ENQUEUED, nextState, problem,
                    frontier.size(), visited.size(), generated, "");
            }
        }
        emit(observer, sequence, SearchEventType.SEARCH_FINISHED, rootState, problem,
            frontier.size(), visited.size(), explored.size(), "");
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

    private void emit(
        SearchObserver observer,
        long[] sequence,
        SearchEventType type,
        SearchState state,
        SearchProblem problem,
        int frontierSize,
        int visitedCount,
        int generatedCount,
        String pruningReason
    ) {
        observer.onEvent(new SearchEvent(
            sequence[0]++,
            type,
            state.expression(),
            state.canonicalHash(),
            state.depth(),
            state.score().weightedTotal(),
            state.parentExpression() == null ? "" : problem.canonicalizer().stableHash(state.parentExpression()),
            state.appliedRuleId(),
            state.appliedRuleKind(),
            state.assumptions(),
            frontierSize,
            visitedCount,
            generatedCount,
            pruningReason
        ));
    }

    private void emitTransformation(
        SearchObserver observer,
        long[] sequence,
        SearchState state,
        Transformation transformation,
        SearchProblem problem,
        int frontierSize,
        int visitedCount,
        int generatedCount,
        String pruningReason
    ) {
        observer.onEvent(new SearchEvent(
            sequence[0]++,
            pruningReason.isBlank() ? SearchEventType.TRANSFORMATION_GENERATED : SearchEventType.STATE_PRUNED_BUDGET,
            transformation.transformedExpression(),
            problem.canonicalizer().stableHash(transformation.transformedExpression()),
            state.depth() + 1,
            problem.scorer().score(transformation.transformedExpression()).weightedTotal(),
            state.canonicalHash(),
            transformation.rule(),
            transformation.kind(),
            transformation.assumptions(),
            frontierSize,
            visitedCount,
            generatedCount,
            pruningReason
        ));
    }

    private String stateKey(SearchState state) {
        return state.canonicalHash() + ":" + String.join(",", sortedValues(state.appliedRuleApplications()));
    }

    private List<String> sortedValues(Set<String> values) {
        return values.stream().sorted().toList();
    }
}
