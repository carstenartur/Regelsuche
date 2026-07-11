package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static de.regelsuche.ast.BinaryOperator.MUL;
import static de.regelsuche.ast.BinaryOperator.POW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import org.junit.jupiter.api.Test;

class EquivalenceAwarePatternMatcherTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void recognizesCompleteSquareAcrossEquivalentAddAndMultiplyTrees() {
        PatternRewriteRule rule = completeSquareRule(RecognitionProfile.arithmeticAc());
        Expr reorderedAndRegrouped = parser.parseTerm("x^2 + a^2 + 2 * a * x");

        assertTrue(rule.matches(reorderedAndRegrouped));
        assertEquals(parser.parseTerm("(x + a)^2"), rule.apply(reorderedAndRegrouped));
    }

    @Test
    void exactRecognitionRemainsTheDefault() {
        PatternRewriteRule rule = completeSquareRule(RecognitionProfile.exact());

        assertFalse(rule.matches(parser.parseTerm("x^2 + a^2 + 2 * a * x")));
        assertTrue(rule.matches(parser.parseTerm("x^2 + 2 * x * a + a^2")));
    }

    @Test
    void acOnlyRecognitionDoesNotInferChangedCoefficients() {
        PatternRewriteRule rule = completeSquareRule(RecognitionProfile.arithmeticAc());

        assertFalse(rule.matches(parser.parseTerm("x^2 + 3 * a * x + (9 / 4) * a^2")));
    }

    @Test
    void infersFractionalPlaceholderFromAllCompleteSquareTerms() {
        PatternRewriteRule rule = completeSquareRule(RecognitionProfile.algebraicAc());

        assertTrue(rule.matches(parser.parseTerm("x^2 + 3 * a * x + (9 / 4) * a^2")));
        assertTrue(rule.matches(parser.parseTerm("x^2 + (4 / 3) * x * y + (4 / 9) * y^2")));
    }

    @Test
    void rejectsCoefficientWhenOtherOccurrencesContradictInferredBinding() {
        PatternRewriteRule rule = completeSquareRule(RecognitionProfile.algebraicAc());

        assertFalse(rule.matches(parser.parseTerm("x^2 + 3 * a * x + a^2")));
        assertFalse(rule.matches(parser.parseTerm("x^2 + 2 * a * y + a^2")));
    }

    @Test
    void repeatedPlaceholderMustBindToTheSameEquivalentMonomial() {
        PatternRewriteRule rule = new PatternRewriteRule(
            "double-term",
            PatternExpr.op(ADD, PatternExpr.var("X"), PatternExpr.var("X")),
            PatternExpr.op(MUL, PatternExpr.num(2), PatternExpr.var("X")),
            RecognitionProfile.algebraicAc()
        );

        assertTrue(rule.matches(parser.parseTerm("2 * value + value * 2")));
        assertFalse(rule.matches(parser.parseTerm("2 * value + 3 * value")));
    }

    @Test
    void fallsBackToExactStructuralBindingsOutsideMonomialFragment() {
        PatternRewriteRule rule = completeSquareRule(RecognitionProfile.algebraicAc());

        assertTrue(rule.matches(parser.parseTerm("x^2 + 2 * x * sin(a) + sin(a)^2")));
        assertFalse(rule.matches(parser.parseTerm("x^2 + 2 * x * a + 1 / 0 * a^2")));
    }

    private PatternRewriteRule completeSquareRule(RecognitionProfile profile) {
        PatternExpr x = PatternExpr.var("X");
        PatternExpr a = PatternExpr.var("A");
        PatternExpr squareX = PatternExpr.op(POW, x, PatternExpr.num(2));
        PatternExpr doubleProduct = PatternExpr.op(
            MUL,
            PatternExpr.op(MUL, PatternExpr.num(2), x),
            a
        );
        PatternExpr squareA = PatternExpr.op(POW, a, PatternExpr.num(2));
        PatternExpr source = PatternExpr.op(ADD, PatternExpr.op(ADD, squareX, doubleProduct), squareA);
        PatternExpr target = PatternExpr.op(POW, PatternExpr.op(ADD, x, a), PatternExpr.num(2));
        return new PatternRewriteRule("complete-square", source, target, profile);
    }
}
