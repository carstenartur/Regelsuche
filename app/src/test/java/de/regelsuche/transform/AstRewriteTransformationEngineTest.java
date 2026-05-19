package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AstRewriteTransformationEngineTest {
    private final AstRewriteTransformationEngine engine = new AstRewriteTransformationEngine();

    @Test
    void appliesNeutralElementRulesAtNestedSubtrees() {
        List<Transformation> transformations = engine.transform("(x + 0) * y");

        assertHasTransformation(transformations, "ast_add_zero_right", "x * y");
    }

    @Test
    void appliesGeneralStructuralRulesToArbitrarySubtrees() {
        List<Transformation> transformations = engine.transform("f * (g + h)");

        assertHasTransformation(transformations, "ast_distribute_left", "f * g + f * h");
    }

    @Test
    void appliesRulesRecursivelyInsideLargerExpressions() {
        List<Transformation> transformations = engine.transform("w + x * (y + z)");

        assertHasTransformation(transformations, "ast_distribute_left", "w + x * y + x * z");
    }

    @Test
    void factorsCommonSubtreesStructurally() {
        List<Transformation> transformations = engine.transform("(a + b) * c + (a + b) * d");

        assertHasTransformation(transformations, "ast_factor_common_left", "(a + b) * (c + d)");
    }

    @Test
    void combinesPowersWithoutQuadraticAnalyzerFallbacks() {
        List<Transformation> transformations = engine.transform("z^2 * z^3");

        assertHasTransformation(transformations, "ast_combine_powers", "z ^ 5");
    }

    @Test
    void expandsBinomialSquareProductStructurally() {
        List<Transformation> transformations = engine.transform("(y + z)*(y + z)");

        assertHasTransformation(transformations, "ast_expand_binomial_square_product", "y ^ 2 + 2 * y * z + z ^ 2");
    }

    @Test
    void rewritesPowerOfPowerWithoutChangingFormatterAssociativity() {
        List<Transformation> transformations = engine.transform("(x^2)^3");

        assertHasTransformation(transformations, "ast_power_of_power", "x ^ 6");
    }

    @Test
    void symPyEngineDoesNotExposeQuadraticFallbackRules() {
        List<Transformation> transformations = new SymPyTransformationEngine().transform("x^2 + 2*x + 1");

        assertFalse(transformations.stream().anyMatch(transformation -> transformation.rule().startsWith("fallback_")));
    }

    private void assertHasTransformation(List<Transformation> transformations, String rule, String target) {
        assertTrue(
            transformations.stream().anyMatch(transformation -> transformation.rule().equals(rule)
                && transformation.transformedExpression().equals(target)),
            () -> "Missing transformation " + rule + " -> " + target + " in " + transformations
        );
    }
}
