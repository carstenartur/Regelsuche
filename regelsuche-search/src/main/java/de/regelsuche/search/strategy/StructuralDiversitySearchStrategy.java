package de.regelsuche.search.strategy;

import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic target-blind quality-diversity control for bounded searches.
 *
 * <p>The strategy keeps at most one elite per structural cell at each depth.
 * Cells are defined only by observable expression and path structure: AST-size
 * band, expansion debt, last rewrite kind, denominator count and power count.
 * No target expression, historical identity or held-out property is used for
 * retention. This makes the strategy suitable as a diagnostic control for
 * scalar-fitness valleys without presenting it as a full MAP-Elites
 * implementation.</p>
 */
public final class StructuralDiversitySearchStrategy implements SearchStrategy {
    @Override
    public List<SearchState> search(SearchProblem problem) {
        Objects.requireNonNull(problem, "problem");
        String root = normalize(problem.rootExpression());
        SearchState rootState = rootState(problem, root);
        List<SearchState> explored = new ArrayList<>();
        List<SearchState> frontier = List.of(rootState);
        Set<String> visited = new HashSet<>();
        int rootAstSize = problem.canonicalizer().astNodeCount(root);

        for (int depth = 0;
                depth <= problem.heuristic().maxDepth() && !frontier.isEmpty();
                depth++) {
            Map<StructuralCell, SearchState> nextElites = new LinkedHashMap<>();
            for (SearchState current : frontier) {
                if (explored.size() >= problem.heuristic().maxVisitedExpressions()) {
                    return List.copyOf(explored);
                }
                if (!visited.add(stateKey(current))) {
                    continue;
                }
                if (current.depth() > 0
                        && TranspositionGate.evaluate(
                            problem.memory(),
                            current,
                            current.canonicalHash() + "#" + current.depth())
                        == TranspositionGate.Verdict.PRUNE) {
                    continue;
                }
                explored.add(current);
                if (current.depth() >= problem.heuristic().maxDepth()) {
                    continue;
                }
                retainSuccessors(problem, current, nextElites, visited);
            }
            frontier = selectFrontier(problem, nextElites, rootAstSize);
        }
        return List.copyOf(explored);
    }

    private SearchState rootState(SearchProblem problem, String root) {
        return new SearchState(
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
    }

    private void retainSuccessors(
        SearchProblem problem,
        SearchState current,
        Map<StructuralCell, SearchState> nextElites,
        Set<String> visited
    ) {
        List<Transformation> transformations = new ArrayList<>(
            problem.engine().transform(current.expression()));
        transformations.sort(Comparator
            .comparing(Transformation::rule)
            .thenComparing(Transformation::transformedExpression)
            .thenComparing(Transformation::applicationKey));

        int generated = 0;
        for (Transformation transformation : transformations) {
            if (generated >= problem.heuristic().maxCandidatesPerState()) {
                break;
            }
            if (current.appliedRuleApplications().contains(
                    transformation.applicationKey())) {
                continue;
            }
            int expandedSteps = current.expandedStepCount()
                + (transformation.kind() == RewriteKind.EXPAND ? 1 : 0);
            if (expandedSteps > problem.heuristic().maxExpandingSteps()) {
                continue;
            }
            String nextExpression = transformation.transformedExpression();
            if (nextExpression.equals(current.expression())) {
                continue;
            }
            SearchState next = nextState(
                problem,
                current,
                transformation,
                nextExpression,
                expandedSteps);
            if (visited.contains(stateKey(next))) {
                continue;
            }
            StructuralCell cell = StructuralCell.of(problem, next);
            nextElites.merge(
                cell,
                next,
                (left, right) -> eliteComparator(problem).compare(left, right) <= 0
                    ? left
                    : right);
            generated++;
        }
    }

    private SearchState nextState(
        SearchProblem problem,
        SearchState current,
        Transformation transformation,
        String nextExpression,
        int expandedSteps
    ) {
        ExpressionScore nextScore = problem.scorer().score(nextExpression);
        int improvement = current.score().weightedTotal()
            - nextScore.weightedTotal();
        List<String> path = appended(current.path(), nextExpression);
        List<String> ruleIds = appended(
            current.appliedRuleIds(),
            transformation.rule());
        Set<String> applications = new HashSet<>(
            current.appliedRuleApplications());
        applications.add(transformation.applicationKey());
        List<RewriteKind> kinds = appended(
            current.appliedRuleKinds(),
            transformation.kind());
        List<Boolean> equivalenceFlags = appended(
            current.equivalencePreservingFlags(),
            transformation.equivalencePreservingByConstruction());
        List<String> assumptions = new ArrayList<>(current.assumptions());
        assumptions.addAll(transformation.assumptions());

        return new SearchState(
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
            kinds,
            equivalenceFlags,
            assumptions
        );
    }

    private List<SearchState> selectFrontier(
        SearchProblem problem,
        Map<StructuralCell, SearchState> elites,
        int rootAstSize
    ) {
        return elites.entrySet().stream()
            .sorted(Comparator
                .<Map.Entry<StructuralCell, SearchState>>comparingInt(entry ->
                    entry.getKey().noveltyFrom(rootAstSize))
                .reversed()
                .thenComparing(entry -> entry.getKey().stableKey())
                .thenComparing(Map.Entry::getValue, eliteComparator(problem)))
            .limit(problem.heuristic().beamWidth())
            .map(Map.Entry::getValue)
            .toList();
    }

    private Comparator<SearchState> eliteComparator(SearchProblem problem) {
        return Comparator
            .comparingInt((SearchState state) -> priority(state, problem))
            .thenComparingInt(SearchState::depth)
            .thenComparing(SearchState::canonicalHash)
            .thenComparing(SearchState::expression)
            .thenComparing(state -> String.join("->", state.appliedRuleIds()))
            .thenComparing(state -> String.join("->", state.path()));
    }

    private int priority(SearchState state, SearchProblem problem) {
        int modelCost = problem.costModel() == null
            ? state.score().weightedTotal()
            : problem.costModel().cost(
                state.expression(),
                problem.canonicalizer(),
                state.score());
        if (modelCost == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE / 2;
        }
        return modelCost
            + state.depth() * 2
            + state.expandedStepCount() * 5
            + (state.improvement() <= 0 && state.depth() > 0 ? 4 : 0);
    }

    private String stateKey(SearchState state) {
        return state.canonicalHash() + ":"
            + String.join(",", state.appliedRuleApplications().stream()
                .sorted()
                .toList());
    }

    private static String normalize(String expression) {
        return Objects.requireNonNull(expression, "expression")
            .trim()
            .replaceAll("\\s+", " ");
    }

    private static <T> List<T> appended(List<T> values, T value) {
        List<T> result = new ArrayList<>(values);
        result.add(value);
        return result;
    }

    private record StructuralCell(
        int astSizeBand,
        int expansionDebt,
        RewriteKind lastKind,
        int denominatorBand,
        int powerBand
    ) {
        private static StructuralCell of(
            SearchProblem problem,
            SearchState state
        ) {
            String expression = state.expression();
            return new StructuralCell(
                Math.min(24,
                    problem.canonicalizer().astNodeCount(expression) / 3),
                Math.min(8, state.expandedStepCount()),
                state.appliedRuleKind(),
                Math.min(4, occurrences(expression, '/')),
                Math.min(6, occurrences(expression, '^'))
            );
        }

        private int noveltyFrom(int rootAstSize) {
            int rootBand = Math.min(24, rootAstSize / 3);
            return Math.abs(astSizeBand - rootBand) * 4
                + expansionDebt * 5
                + (lastKind == RewriteKind.EXPAND ? 4 : 0)
                + (lastKind == RewriteKind.FACTOR ? 3 : 0)
                + denominatorBand * 2
                + powerBand;
        }

        private String stableKey() {
            return astSizeBand + ":" + expansionDebt + ":" + lastKind.name()
                + ":" + denominatorBand + ":" + powerBand;
        }

        private static int occurrences(String expression, char marker) {
            int count = 0;
            for (int index = 0; index < expression.length(); index++) {
                if (expression.charAt(index) == marker) {
                    count++;
                }
            }
            return count;
        }
    }
}
