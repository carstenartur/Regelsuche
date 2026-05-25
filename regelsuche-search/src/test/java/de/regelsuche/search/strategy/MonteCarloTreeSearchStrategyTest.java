package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.transform.AstRewriteTransformationEngine;
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
        long distinctKeys = states.stream()
            .map(state -> state.canonicalHash() + ":" + state.appliedRuleApplications())
            .distinct()
            .count();
        assertTrue(states.size() == distinctKeys, "hybrid strategy must not duplicate states");
    }
}
