package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.app.transform.SymPyTransformationEngine;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class AstRewriteTransformationEngineTest {
    private final AstRewriteTransformationEngine engine = new AstRewriteTransformationEngine();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    @Test
    void doesNotContainDirectBinomialRewriteRule() {
        assertFalse(AstRewriteTransformationEngine.defaultRules().stream().map(RewriteRule::id).anyMatch(this::isForbiddenTextbookRule));
    }

    @Test
    void doesNotContainDirectDifferenceOfSquaresRewriteRule() {
        assertFalse(AstRewriteTransformationEngine.defaultRules().stream().map(RewriteRule::id).anyMatch(this::isForbiddenTextbookRule));
    }

    @Test
    void derivesBinomialExpansionFromAtomicRulesOnly() {
        AtomicPath path = applyPath(
            "(x+a)^2",
            "ast_power_two_to_product",
            "ast_distribute_right_add",
            "ast_distribute_left_add",
            "ast_distribute_left_add",
            "ast_canonical_normalize"
        );
        String expected = canonicalizer.canonicalize("x*x + x*a + a*x + a*a");

        assertTrue(path.ruleIds().contains("ast_power_two_to_product"));
        assertTrue(path.ruleIds().stream().anyMatch(id -> id.startsWith("ast_distribute")));
        assertFalse(path.ruleIds().stream().anyMatch(this::isForbiddenTextbookRule));
        assertTrue(canonicalizer.canonicalize(path.expression()).equals(expected));
    }

    @Test
    void derivesDifferenceOfSquaresFromAtomicRulesOnly() {
        AtomicPath path = applyPath(
            "(x+a)*(x-a)",
            "ast_distribute_right_add",
            "ast_distribute_left_subtract",
            "ast_distribute_left_subtract",
            "ast_canonical_normalize"
        );
        String expected = canonicalizer.canonicalize("x*x - a*a");

        assertTrue(path.ruleIds().stream().anyMatch(id -> id.startsWith("ast_distribute")));
        assertFalse(path.ruleIds().stream().anyMatch(this::isForbiddenTextbookRule));
        assertTrue(canonicalizer.canonicalize(path.expression()).equals(expected));
    }

    @Test
    void rewritesNestedSubtrees() {
        List<Transformation> transformations = engine.transform("w + x * (y + z)");

        assertHasTransformation(transformations, "ast_distribute_left_add", "w + x * y + x * z");
    }

    @Test
    void preventsSimpleRewriteCycles() {
        List<SearchState> states = search("x^2", 6, 80, 4);

        assertTrue(states.size() < 80);
        assertTrue(states.stream().allMatch(state -> state.appliedRuleIds().stream()
            .filter("ast_power_two_to_product"::equals)
            .count() <= 1));
    }

    @Test
    void limitsExpansionExplosion() {
        AstRewriteTransformationEngine limited = new AstRewriteTransformationEngine(
            AstRewriteTransformationEngine.defaultRules(),
            50,
            3
        );

        assertTrue(limited.transform("(a + b) * (c + d) + (e + f) * (g + h)").size() <= 3);
    }

    @Test
    void symPyEngineDoesNotExposeQuadraticFallbackRules() {
        List<Transformation> transformations = new SymPyTransformationEngine().transform("x^2 + 2*x + 1");

        assertFalse(transformations.stream().anyMatch(transformation -> transformation.rule().startsWith("fallback_")));
    }

    private List<SearchState> search(String expression, int maxDepth, int maxVisited, int maxExpansionSteps) {
        return new BestFirstSearchStrategy().search(new SearchProblem(
            expression,
            engine,
            new ExpressionScorer(),
            canonicalizer,
            new SearchHeuristic(maxDepth, maxVisited, 1, maxExpansionSteps, 160, 30)
        ));
    }

    private AtomicPath applyPath(String expression, String... ruleIds) {
        String current = expression;
        List<String> applied = new java.util.ArrayList<>();
        for (String ruleId : ruleIds) {
            String source = current;
            Transformation transformation = engine.transform(current).stream()
                .filter(candidate -> candidate.rule().equals(ruleId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing atomic step " + ruleId + " from " + source));
            current = transformation.transformedExpression();
            applied.add(ruleId);
        }
        return new AtomicPath(current, applied);
    }

    private record AtomicPath(String expression, List<String> ruleIds) {
    }

    private boolean isForbiddenTextbookRule(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        return normalized.contains("binomial")
            || normalized.contains("difference_of_squares")
            || normalized.contains("perfect_square")
            || normalized.contains("quadratic_completion")
            || normalized.contains("book_formula");
    }

    private void assertHasTransformation(List<Transformation> transformations, String rule, String target) {
        assertTrue(
            transformations.stream().anyMatch(transformation -> transformation.rule().equals(rule)
                && transformation.transformedExpression().equals(target)),
            () -> "Missing transformation " + rule + " -> " + target + " in " + transformations
        );
    }
}
