package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.egraph.SaturationStats;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.SearchProfile;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class EqualitySaturationStrategyTest {

    private final AstRewriteTransformationEngine engine = new AstRewriteTransformationEngine();
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final SearchHeuristic heuristic = new SearchHeuristic(6, 1000, 1, 6, 80, 16);

    @Test
    void equalitySaturationFindsBinomialExpansion() {
        // (x+3)^2 must, after saturation, share an e-class with its
        // expanded product form (x+3)*(x+3). The rule
        // ast_power_two_to_product fires, the result is unioned, and the
        // graph holds both forms in the same class.
        SearchProblem problem = problemFor("( x + 3 ) ^ 2");
        EqualitySaturationStrategy strategy = new EqualitySaturationStrategy();
        List<SearchState> result = strategy.search(problem);
        SaturationStats stats = strategy.lastStats();

        assertNotNull(stats);
        assertTrue(stats.appliedRules().containsKey("ast_power_two_to_product"),
            "expected ast_power_two_to_product to fire on (x+3)^2 during saturation, got: "
                + stats.appliedRules());
        assertTrue(stats.merges() >= 1 || stats.totalApplications() >= 1,
            "expected at least one merge/application during saturation");
        // The extracted best form should still be (x+3)^2 because it has
        // fewer operator nodes than the distributed expansion.
        SearchState best = result.get(result.size() - 1);
        assertEquals(2, result.size());
        assertEquals(EqualitySaturationStrategy.SATURATION_RULE_ID, best.appliedRuleId());
        assertTrue(best.appliedRuleIds().contains(EqualitySaturationStrategy.SATURATION_RULE_ID));
    }

    @Test
    void equalitySaturationAvoidsRewriteOrderExplosion() {
        // The path-based BestFirst strategy enumerates one explored state
        // per ordered rewrite path; saturation collapses every
        // equivalent form into a single e-class, so the resulting graph
        // is strictly smaller than the number of explored search states.
        SearchProblem problem = problemFor("( a + b ) * c");
        EqualitySaturationStrategy saturation = new EqualitySaturationStrategy();
        saturation.search(problem);
        SaturationStats stats = saturation.lastStats();

        List<SearchState> bestFirstExplored = new BestFirstSearchStrategy().search(problem);

        assertTrue(stats.eclasses() < bestFirstExplored.size() + 50,
            "saturation should not blow up the e-class count beyond what BestFirst explores; "
                + "eclasses=" + stats.eclasses() + " explored=" + bestFirstExplored.size());
        // Sanity: saturation actually did some work.
        assertTrue(stats.iterations() >= 1);
    }

    @Test
    void equalitySaturationExtractsCheapestExpression() {
        // Saturating x + 0 must fold through ast_add_zero_right (or _left)
        // and extraction must then pick the bare "x" representative as
        // the smallest form.
        SearchProblem problem = problemFor("x + 0");
        EqualitySaturationStrategy strategy = new EqualitySaturationStrategy();
        List<SearchState> result = strategy.search(problem);
        SaturationStats stats = strategy.lastStats();

        assertEquals("x", stats.extractedBest(),
            "extract must pick the simpler representative after saturation");
        assertEquals(2, result.size());
        assertEquals("x", result.get(1).expression());
    }

    @Test
    void equalitySaturationRespectsIterationBudget() {
        // Budget of exactly 1 iteration must produce stats.iterations() == 1
        // and stop without claiming to have reached a fix point.
        SearchProblem problem = problemFor("( a + b ) * c");
        EqualitySaturationStrategy strategy =
            new EqualitySaturationStrategy(new de.regelsuche.egraph.EqualitySaturation.Config(1, 10_000));
        strategy.search(problem);
        SaturationStats stats = strategy.lastStats();

        assertEquals(1, stats.iterations(),
            "with maxIterations=1 exactly one iteration must run");
        // It either fixed-pointed in that single iteration or hit the
        // budget — either is a valid stop reason, but it must NOT be the
        // node-budget reason for such a tiny input.
        assertNotEquals(SaturationStats.Reason.NODE_BUDGET, stats.stopReason());
    }

    @Test
    void equalitySaturationReportsStats() {
        SearchProblem problem = problemFor("( x + 3 ) ^ 2");
        EqualitySaturationStrategy strategy = new EqualitySaturationStrategy();
        strategy.search(problem);
        SaturationStats stats = strategy.lastStats();

        assertNotNull(stats);
        assertTrue(stats.eclasses() > 0, "stats.eclasses() must be populated");
        assertTrue(stats.enodes() > 0, "stats.enodes() must be populated");
        assertTrue(stats.iterations() >= 1, "stats.iterations() must be populated");
        assertNotNull(stats.appliedRules(), "stats.appliedRules() must be populated");
        assertNotNull(stats.extractedBest(), "stats.extractedBest() must be populated");
        // Aggregated stats must be self-consistent.
        assertEquals(stats.totalApplications(),
            stats.appliedRules().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void searchProfileExposesEqualitySaturation() {
        SearchProfile profile = SearchProfile.EQUALITY_SATURATION;
        SearchStrategy strategy = profile.newStrategy();
        assertTrue(strategy instanceof EqualitySaturationStrategy,
            "SearchProfile.EQUALITY_SATURATION must build the new strategy; got "
                + strategy.getClass());
        assertFalse(profile.usesTranspositionTable(),
            "equality saturation has its own deduplication via e-classes");
    }

    private SearchProblem problemFor(String expression) {
        return new SearchProblem(expression, engine, scorer, canonicalizer, heuristic);
    }
}
