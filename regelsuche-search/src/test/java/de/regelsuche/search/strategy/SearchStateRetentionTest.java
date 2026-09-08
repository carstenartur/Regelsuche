package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.transform.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SearchStateRetentionTest {
    @Test void everyOrdinaryStrategyRetainsActualMacroLineageAndAssumptions() {
        var macro = new Transformation("macro", "x", RewriteKind.SIMPLIFY, false, -2, true,
            "macro-at-root", List.of("a != 0"), "test", "PROJECT", List.of("r1", "r2"));
        TransformationEngine engine = expression -> expression.equals("x + 0 + 0") ? List.of(macro) : List.of();
        var problem = new SearchProblem("x + 0 + 0", engine, new ExpressionScorer(), new ExpressionCanonicalizer(),
            new SearchHeuristic(3, 10, 1, 2, 5, 3));
        for (SearchStrategy strategy : List.of(new BestFirstSearchStrategy(), new BeamSearchStrategy(),
                new StructuralDiversitySearchStrategy(), new RandomMonteCarloSearchStrategy(7), new MonteCarloTreeSearchStrategy(7))) {
            var states = strategy.search(problem);
            var reached = states.stream().filter(state -> state.expression().equals("x")).findFirst().orElseThrow();
            assertEquals(List.of(macro), reached.transformations(), strategy.getClass().getSimpleName());
            assertEquals(macro.assumptions(), reached.assumptions());
            assertEquals(new ExecutionWork(2, 0, 0), reached.executionWork().orElseThrow());
            assertEquals(1, reached.depth());
            reached.recordedExecution().orElseThrow().requireReplay(problem.rootExpression(), List.of(macro));
            assertThrows(IllegalArgumentException.class, () -> reached.withAssumptions(List.of()));
        }
    }

    @Test void syntheticObservationDoesNotInventAFreeOrPrimitiveExecution() {
        var state = new SearchState("x", 1, new ExpressionScorer().score("x"), List.of("x + 0", "x"),
            List.of("egraph-extract"), Set.of("observed"), 0, "canonical", "x + 0", "egraph-extract",
            RewriteKind.NORMALIZE, false, 0, true, 1);
        assertTrue(state.recordedExecution().isEmpty());
        assertTrue(state.executionWork().isEmpty());
        assertTrue(SearchStateReplay.toCanonicalJson(state).contains("\"executionRetained\":false"));
    }

    @Test void diagnosticPlaceholderWithoutAPathIsNotAnExecutedRoot() {
        var placeholder = new SearchState("x", 0, null, List.of(), List.of(), Set.of(), 0,
            "canonical", "", "", RewriteKind.NORMALIZE, false, 0, true, 0);
        assertTrue(placeholder.recordedExecution().isEmpty());
        assertTrue(placeholder.executionWork().isEmpty());
        assertTrue(SearchStateReplay.toCanonicalJson(placeholder).contains("\"score\":null"));
        var historic = new SearchState("x", 1, new ExpressionScorer().score("x"), List.of("x + 0", "x"),
            List.of("recorded rule"), Set.of(), 0, "canonical", null, null, null, false, 0, true, 0);
        String json = SearchStateReplay.toCanonicalJson(historic);
        assertTrue(json.contains("\"appliedRuleKind\":null"));
        assertTrue(json.contains("\"executionRetained\":false"));
    }
}
