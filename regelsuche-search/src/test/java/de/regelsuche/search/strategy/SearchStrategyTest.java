package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
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

    @Test
    void randomMonteCarloIsReusableOrderStableAndPreservesAssumptions() {
        SearchProblem forward = new SearchProblem(
            "x",
            new AssumptionTransformationEngine(false),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(1, 8, 1, 2, 8, 8)
        );
        SearchProblem reversed = new SearchProblem(
            "x",
            new AssumptionTransformationEngine(true),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(1, 8, 1, 2, 8, 8)
        );
        RandomMonteCarloSearchStrategy strategy =
            new RandomMonteCarloSearchStrategy(4);

        List<SearchState> first = strategy.search(forward);
        List<SearchState> second = strategy.search(forward);
        List<SearchState> reordered =
            new RandomMonteCarloSearchStrategy(4).search(reversed);

        assertEquals(snapshot(first), snapshot(second));
        assertEquals(snapshot(first), snapshot(reordered));
        assertEquals(
            List.of("x != 0"),
            first.stream()
                .filter(state -> state.expression().equals("y"))
                .findFirst()
                .orElseThrow()
                .assumptions()
        );
    }

    @Test
    void bestFirstSortsTransformationsBeforeApplyingCandidateLimit() {
        SearchProblem problem = new SearchProblem(
            "x",
            new UnorderedTransformationEngine(),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(1, 8, 1, 2, 1, 8)
        );

        List<SearchState> states = new BestFirstSearchStrategy().search(problem);

        assertEquals(List.of("x", "a"), states.stream().map(SearchState::expression).toList());
    }

    @Test
    void beamSearchSortsTransformationsBeforeApplyingCandidateLimit() {
        SearchProblem problem = new SearchProblem(
            "x",
            new UnorderedTransformationEngine(),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(1, 8, 1, 2, 1, 8)
        );

        List<SearchState> states = new BeamSearchStrategy().search(problem);

        assertEquals(List.of("x", "a"), states.stream().map(SearchState::expression).toList());
    }

    private static List<String> snapshot(List<SearchState> states) {
        return states.stream()
            .map(state -> state.expression() + "|"
                + String.join(",", state.appliedRuleIds()) + "|"
                + String.join(",", state.assumptions()))
            .toList();
    }

    private static final class UnorderedTransformationEngine implements TransformationEngine {
        @Override
        public List<Transformation> transform(String expression) {
            if (!"x".equals(expression)) {
                return List.of();
            }
            return List.of(
                new Transformation("rule_z", "z", RewriteKind.NORMALIZE, false, 0, true, "rule_z:z"),
                new Transformation("rule_a", "a", RewriteKind.NORMALIZE, false, 0, true, "rule_a:a")
            );
        }
    }

    private static final class AssumptionTransformationEngine
            implements TransformationEngine {
        private final boolean reverse;

        private AssumptionTransformationEngine(boolean reverse) {
            this.reverse = reverse;
        }

        @Override
        public List<Transformation> transform(String expression) {
            if (!"x".equals(expression)) {
                return List.of();
            }
            Transformation z = new Transformation(
                "rule_z",
                "z",
                RewriteKind.NORMALIZE,
                false,
                0,
                true,
                "rule_z:x"
            );
            Transformation y = new Transformation(
                "rule_y",
                "y",
                RewriteKind.NORMALIZE,
                false,
                0,
                true,
                "rule_y:x",
                List.of("x != 0")
            );
            return reverse ? List.of(y, z) : List.of(z, y);
        }
    }
}
