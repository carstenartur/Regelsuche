package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import org.junit.jupiter.api.Test;

class SearchStrategyTest {
    @Test
    void beamSearchFindsShorterRepresentation() {
        SearchProblem problem = new SearchProblem(
            "(x + 0) * 1",
            new AstRewriteTransformationEngine(),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(4, 80, 1, 2, 40, 8)
        );

        assertTrue(new BeamSearchStrategy().search(problem).stream().anyMatch(state -> state.expression().equals("x")));
    }

    @Test
    void aStarAndRandomMonteCarloExploreValidSearchStates() {
        SearchProblem problem = new SearchProblem(
            "(x + 0) * (y + 1)",
            new AstRewriteTransformationEngine(),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(4, 80, 1, 2, 40, 8)
        );

        assertTrue(new AStarSearchStrategy().search(problem).stream().anyMatch(state -> state.appliedRuleIds().contains("ast_add_zero_right")));
        assertTrue(new RandomMonteCarloSearchStrategy(4).search(problem).size() > 1);
    }
}
