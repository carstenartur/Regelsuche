package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.SearchProfile;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuralDiversitySearchStrategyTest {
    private static final String ROOT = "x + 0";
    private static final String TARGET = "x + y - y";

    @Test
    void targetBlindStructuralCellsRetainAnExpansionThatScalarPriorityStarves() {
        SearchProblem problem = problem();

        List<SearchState> scalar = new BestFirstSearchStrategy().search(problem);
        List<SearchState> diverse = new StructuralDiversitySearchStrategy().search(problem);

        assertFalse(contains(scalar, TARGET), scalar.toString());
        assertTrue(contains(diverse, TARGET), diverse.toString());
        assertTrue(scalar.size() <= problem.heuristic().maxVisitedExpressions());
        assertTrue(diverse.size() <= problem.heuristic().maxVisitedExpressions());
        assertEquals(null, problem.target(), "the diversity control must remain target-blind");
    }

    @Test
    void outputIsDeterministicAndTheProfileExposesTheControl() {
        SearchProblem problem = problem();
        StructuralDiversitySearchStrategy strategy =
            new StructuralDiversitySearchStrategy();

        assertEquals(strategy.search(problem), strategy.search(problem));
        assertInstanceOf(
            StructuralDiversitySearchStrategy.class,
            SearchProfile.DIVERSITY_DISCOVERY.newStrategy());
    }

    private SearchProblem problem() {
        return new SearchProblem(
            ROOT,
            engine(),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(2, 3, 1, 2, 8, 2)
        );
    }

    private TransformationEngine engine() {
        return expression -> switch (expression) {
            case ROOT -> List.of(
                edge("simplify-root", "x", RewriteKind.SIMPLIFY, false, -2),
                edge("expand-root", TARGET, RewriteKind.EXPAND, true, 5));
            case "x" -> List.of(
                edge("reintroduce-neutral", "x * 1", RewriteKind.NORMALIZE, false, 0));
            default -> List.of();
        };
    }

    private Transformation edge(
        String rule,
        String target,
        RewriteKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta
    ) {
        return new Transformation(
            rule,
            target,
            kind,
            mayIncreaseComplexity,
            estimatedCostDelta,
            true,
            rule + ":root"
        );
    }

    private boolean contains(List<SearchState> states, String expression) {
        return states.stream().anyMatch(state -> state.expression().equals(expression));
    }
}
