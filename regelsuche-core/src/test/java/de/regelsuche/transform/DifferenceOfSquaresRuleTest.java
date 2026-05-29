package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DifferenceOfSquaresRuleTest {
    @Test
    void rewritesGenericDifferenceOfSquares() {
        AstRewriteTransformationEngine engine = new AstRewriteTransformationEngine();

        assertTrue(engine.transform("u^2 - v^2").stream().anyMatch(transformation ->
            transformation.rule().equals("ast_square_difference_factor")
                && transformation.transformedExpression().equals("(u - v) * (u + v)")
                && transformation.kind() == RewriteKind.FACTOR));
    }

    @Test
    void rewriteIsNotTiedToSpecificVariables() {
        AstRewriteTransformationEngine engine = new AstRewriteTransformationEngine();

        assertTrue(engine.transform("alpha^2 - beta^2").stream().anyMatch(transformation ->
            transformation.rule().equals("ast_square_difference_factor")
                && transformation.transformedExpression().equals("(alpha - beta) * (alpha + beta)")));
    }
}
