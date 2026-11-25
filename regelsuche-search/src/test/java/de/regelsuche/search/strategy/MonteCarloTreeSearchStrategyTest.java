package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonteCarloTreeSearchStrategyTest {

    @Test
    void exploresStatesWithinBudget() {
        SearchProblem problem = new SearchProblem(
            "(x + 1) * (x + 1)",
            new AstRewriteTransformationEngine(),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(4, 60, 1, 4, 24, 8)
        );
        List<SearchState> states = new MonteCarloTreeSearchStrategy(123L).search(problem);
        assertFalse(states.isEmpty());
        assertTrue(states.size() <= 60);
    }

    @Test
    void hybridStrategyDeduplicates() {
        SearchProblem problem = new SearchProblem(
            "x + 0",
            new AstRewriteTransformationEngine(),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(3, 30, 1)
        );
        List<SearchState> states = new HybridSearchStrategy().search(problem);
        assertEquals(states.size(), states.stream().distinct().count(),
            "hybrid strategy must not duplicate exact states");
    }

    @Test
    void preservesDistinctAssumptionsWithTheSameValueAndApplication() {
        SearchProblem problem = problem("x + 0", expression -> expression.equals("x + 0")
            ? List.of(step("x", "same", RewriteKind.NORMALIZE, "x != 0"),
                      step("x", "same", RewriteKind.NORMALIZE, "y != 0"))
            : List.of(), 2, 10, 1);
        List<SearchState> states = new MonteCarloTreeSearchStrategy(7).search(problem);
        var leaves = states.stream().filter(state -> state.depth() == 1).toList();
        assertEquals(2, leaves.size());
        assertEquals(1, leaves.stream().map(SearchState::canonicalHash).distinct().count());
        assertEquals(1, leaves.stream().map(SearchState::appliedRuleApplications).distinct().count());
        assertEquals(2, leaves.stream().map(SearchState::assumptions).distinct().count());
        assertTrue(leaves.stream().allMatch(state -> state.recordedExecution().isPresent()));
    }

    @Test
    void preservesDifferentSyntaxEvenWhenValueAndApplicationAgree() {
        SearchProblem problem = problem("x + y + 0", expression -> expression.equals("x + y + 0")
            ? List.of(step("x + y", "same", RewriteKind.NORMALIZE),
                      step("y + x", "same", RewriteKind.NORMALIZE))
            : List.of(), 2, 10, 1);
        var leaves = new MonteCarloTreeSearchStrategy(7).search(problem).stream()
            .filter(state -> state.depth() == 1).toList();
        assertEquals(2, leaves.size());
        assertEquals(1, leaves.stream().map(SearchState::canonicalHash).distinct().count());
        assertEquals(2, leaves.stream().map(SearchState::expression).distinct().count());
    }

    @Test
    void hybridRetainsDifferentEvidenceButDeduplicatesAnExactRepeat() {
        SearchProblem first = problem("x + 0", expression -> expression.equals("x + 0")
            ? List.of(step("x", "same", RewriteKind.NORMALIZE, "x != 0")) : List.of(), 2, 10, 1);
        SearchProblem second = problem("x + 0", expression -> expression.equals("x + 0")
            ? List.of(step("x", "same", RewriteKind.NORMALIZE, "y != 0")) : List.of(), 2, 10, 1);
        var left = new MonteCarloTreeSearchStrategy(7).search(first);
        var right = new MonteCarloTreeSearchStrategy(7).search(second);
        var hybrid = new HybridSearchStrategy(List.of(ignored -> left, ignored -> right, ignored -> left));
        var result = hybrid.search(first);
        assertEquals(2, result.stream().filter(state -> state.depth() == 1).count());
        assertEquals(result.size(), result.stream().distinct().count());
        assertTrue(result.containsAll(left));
        assertTrue(result.containsAll(right));
    }

    @Test
    void rolloutsDoNotQueryBeyondTheDepthLimit() {
        var queried = new ArrayList<String>();
        SearchProblem problem = problem("x", expression -> {
            queried.add(expression);
            return expression.equals("x") ? List.of(step("x + 0", "first", RewriteKind.NORMALIZE))
                : List.of(step("x + 0 + 0", "second", RewriteKind.NORMALIZE));
        }, 1, 2, 1);
        var result = new MonteCarloTreeSearchStrategy(7).search(problem);
        assertEquals(2, result.size());
        assertEquals(List.of("x"), queried);
    }

    @Test
    void rolloutsRespectExpansionAndRepeatedApplicationLimits() {
        for (Transformation forbidden : List.of(
                step("x + 0 + 0", "second", RewriteKind.EXPAND),
                step("x + 0 + 0", "first", RewriteKind.NORMALIZE))) {
            var queried = new ArrayList<String>();
            SearchProblem problem = problem("x", expression -> {
                queried.add(expression);
                return switch (expression) {
                    case "x" -> List.of(step("x + 0", "first", RewriteKind.NORMALIZE));
                    case "x + 0" -> List.of(forbidden);
                    default -> List.of();
                };
            }, 4, 2, 0);
            var result = new MonteCarloTreeSearchStrategy(7).search(problem);
            assertEquals(2, result.size());
            assertEquals(List.of("x", "x + 0"), queried);
        }
    }

    @Test
    void creditsDescendantRolloutsToAncestorsInsteadOfStarvingAnAlternative() {
        // Single variables have identical scores: only the exploration term
        // distinguishes branches. Seed 7 initially rolls out the second child.
        SearchProblem problem = problem("x", expression -> switch (expression) {
            case "x" -> List.of(step("a", "a", RewriteKind.NORMALIZE),
                step("b", "b", RewriteKind.NORMALIZE));
            case "a" -> List.of(step("c", "c", RewriteKind.NORMALIZE));
            case "c" -> List.of(step("d", "d", RewriteKind.NORMALIZE));
            case "d" -> List.of(step("e", "e", RewriteKind.NORMALIZE));
            case "b" -> List.of(step("f", "f", RewriteKind.NORMALIZE));
            default -> List.of();
        }, 5, 6, 0);
        var states = new MonteCarloTreeSearchStrategy(7L, 1, 1).search(problem);
        assertEquals(6, states.size());
        assertEquals(1, states.stream().map(state -> state.score().weightedTotal()).distinct().count());
        assertTrue(states.stream().anyMatch(state -> state.path().equals(List.of("x", "a", "c"))));
        assertTrue(states.stream().anyMatch(state -> state.path().equals(List.of("x", "b", "f"))),
            "descendant visits must reduce the exploration bonus of their ancestor");
        assertTrue(states.stream().allMatch(state -> state.recordedExecution().isPresent()));
    }

    private static SearchProblem problem(String root, TransformationEngine engine,
            int depth, int states, int expansions) {
        return new SearchProblem(root, engine, new ExpressionScorer(), new ExpressionCanonicalizer(),
            new SearchHeuristic(depth, states, 1, expansions, 8, 4));
    }

    private static Transformation step(String result, String application, RewriteKind kind,
            String... assumptions) {
        return new Transformation("synthetic-control", result, kind, kind == RewriteKind.EXPAND,
            0, true, application, List.of(assumptions));
    }
}
