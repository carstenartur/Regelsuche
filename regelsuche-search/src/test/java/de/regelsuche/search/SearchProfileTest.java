package de.regelsuche.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchProfileTest {

    @Test
    void allProfilesProvideHeuristicAndStrategy() {
        for (SearchProfile profile : SearchProfile.values()) {
            assertNotNull(profile.heuristic(), () -> "missing heuristic for " + profile);
            assertNotNull(profile.newStrategy(), () -> "missing strategy for " + profile);
        }
    }

    @Test
    void hybridProfileProducesExploredStates() {
        SearchProblem problem = new SearchProblem(
            "x + 0",
            new AstRewriteTransformationEngine(),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            SearchProfile.DISCOVERY.heuristic()
        );
        List<SearchState> states = SearchProfile.DISCOVERY.newStrategy().search(problem);
        assertFalse(states.isEmpty(), "DISCOVERY profile must explore the input");
        assertEquals("x + 0", states.get(0).expression());
    }
}
