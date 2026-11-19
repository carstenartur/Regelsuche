package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.memory.SearchMemory;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class BestFirstMemoryContinuationTest {
    private static final String ROOT = "((x^2)^2)^2";

    @Test
    void freshMemoryPreservesProductionRuleContinuations() {
        var strategy = new BestFirstSearchStrategy();
        var problem = powerProblem(4);
        var expected = strategy.searchWithDiagnostics(problem);
        var memory = new SearchMemory();

        var actual = strategy.searchWithDiagnostics(problem.withMemory(memory));

        assertSameReduction(expected, actual, memory);
    }

    @Test
    void repeatedSearchReconstructsTheSameUntargetedReduction() {
        var strategy = new BestFirstSearchStrategy();
        var problem = powerProblem(4);
        var expected = strategy.searchWithDiagnostics(problem);
        var memory = new SearchMemory();
        strategy.searchWithDiagnostics(problem.withMemory(memory));

        var repeated = strategy.searchWithDiagnostics(problem.withMemory(memory));

        assertSameReduction(expected, repeated, memory);
        var reduced = reducedState(repeated);
        assertTrue(memory.table().lookup(reduced.canonicalHash()).orElseThrow().visitCount() >= 2);
    }

    @Test
    void observationsFromAShallowRunDoNotSuppressADeeperContinuation() {
        var strategy = new BestFirstSearchStrategy();
        var memory = new SearchMemory();
        var shallow = strategy.searchWithDiagnostics(powerProblem(1).withMemory(memory));
        assertFalse(shallow.states().stream().anyMatch(state -> state.expression().equals("x ^ 8")));
        assertFalse(memory.table().entries().isEmpty());
        var problem = powerProblem(4);
        var expected = strategy.searchWithDiagnostics(problem);

        var actual = strategy.searchWithDiagnostics(problem.withMemory(memory));

        assertSameReduction(expected, actual, memory);
    }

    @Test
    void valueTargetCanContinueToTheSameReductionAfterBeingReached() {
        var strategy = new BestFirstSearchStrategy();
        var problem = powerProblem(4).withTarget(
            SearchProblem.SearchTarget.valueEquivalent("x ^ 8").continueAfterReached());
        var expected = strategy.searchWithDiagnostics(problem);
        var memory = new SearchMemory();

        var actual = strategy.searchWithDiagnostics(problem.withMemory(memory));

        assertTrue(expected.reached());
        assertTrue(actual.reached());
        assertSameReduction(expected, actual, memory);
        assertEquals(expected.reachedState(), actual.reachedState());
    }

    @Test
    void aStarPreservesContinuationsWithObservationsFromBestFirst() {
        var problem = powerProblem(4);
        var memory = new SearchMemory();
        new BestFirstSearchStrategy().searchWithDiagnostics(problem.withMemory(memory));
        var strategy = new AStarSearchStrategy();
        var expected = strategy.searchWithDiagnostics(problem);

        var actual = strategy.searchWithDiagnostics(problem.withMemory(memory));

        assertSameReduction(expected, actual, memory);
    }

    private static SearchProblem powerProblem(int maxDepth) {
        var engine = new AstRewriteTransformationEngine(
            AstRewriteTransformationEngine.defaultRules().stream()
                .filter(rule -> rule.id().equals("ast_power_of_power")).toList());
        return new SearchProblem(ROOT, engine, new ExpressionScorer(), new ExpressionCanonicalizer(),
            new SearchHeuristic(maxDepth, 32, 1, 4, 32, 16));
    }

    private static void assertSameReduction(
            BestFirstSearchStrategy.GoalSearchResult expected,
            BestFirstSearchStrategy.GoalSearchResult actual,
            SearchMemory memory) {
        assertEquals(List.of(ROOT, "(x ^ 4) ^ 2", "x ^ 8"), reducedState(expected).path());
        assertEquals(expected.states(), actual.states(),
            "Durable value observations must preserve this run's application histories and continuations");
        assertEquals(expected.bestState(), actual.bestState());
        assertEquals(expected.status(), actual.status());
        assertEquals(0, actual.metrics().transpositionPrunes());
        assertEquals(0, actual.metrics().depthPrunes());
        assertEquals(0, actual.metrics().candidateBudgetPrunes());
        assertTrue(memory.decisions().isEmpty());
    }

    private static SearchState reducedState(BestFirstSearchStrategy.GoalSearchResult result) {
        return result.states().stream().filter(state -> state.expression().equals("x ^ 8"))
            .findFirst().orElseThrow();
    }
}
