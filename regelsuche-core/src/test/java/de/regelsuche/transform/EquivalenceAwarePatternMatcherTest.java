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
        Expr reorderedAndRegrouped = parser.parseTerm("a^2 + 2 * a * x + x^2");

        assertTrue(rule.matches(reorderedAndRegrouped));
        assertEquals(parser.parseTerm("(x + a)^2"), rule.apply(reorderedAndRegrouped));
    }

    @Test
    void exactRecognitionRemainsTheDefault() {
        PatternRewriteRule rule = completeSquareRule(RecognitionProfile.exact());

        assertFalse(rule.matches(parser.parseTerm("a^2 + 2 * a * x + x^2")));
        assertTrue(rule.matches(parser.parseTerm("x^2 + 2 * x * a + a^2")));
    }

    @Test
    void rejectsNearMissDespiteAssociativeCommutativeRecognition() {
        PatternRewriteRule rule = completeSquareRule(RecognitionProfile.arithmeticAc());

        assertFalse(rule.matches(parser.parseTerm("a^2 + 3 * a * x + x^2")));
        assertFalse(rule.matches(parser.parseTerm("a^2 + 2 * a * y + x^2")));
    }

    @Test
    void repeatedPlaceholderMustBindToTheSameExpression() {
        PatternRewriteRule rule = new PatternRewriteRule(
            "double-term",
            PatternExpr.op(ADD, PatternExpr.var("X"), PatternExpr.var("X")),
            PatternExpr.op(MUL, PatternExpr.num(2), PatternExpr.var("X")),
            RecognitionProfile.arithmeticAc()
        );

        assertTrue(rule.matches(parser.parseTerm("value + value")));
        assertFalse(rule.matches(parser.parseTerm("value + other")));
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
