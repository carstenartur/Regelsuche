package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static de.regelsuche.ast.BinaryOperator.MUL;
import static de.regelsuche.ast.BinaryOperator.POW;
import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Demonstrates the additional rule applications enabled by broader recognition profiles. */
class RecognitionCoverageComparisonTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void broaderProfilesIncreaseCoverageOnEquivalentCompleteSquareRepresentations() {
        List<Expr> equivalentSquares = List.of(
            parser.parseTerm("x^2 + 2*x*a + a^2"),
            parser.parseTerm("x^2 + a^2 + 2*a*x"),
            parser.parseTerm("a^2 + x^2 + 2*x*a"),
            parser.parseTerm("x*x + 3*x*a + 2.25*a*a"),
            parser.parseTerm("9*a^2/4 + 3*a*x + x^2")
        );

        assertEquals(1, matchingCount(RecognitionProfile.exact(), equivalentSquares));
        assertEquals(3, matchingCount(RecognitionProfile.arithmeticAc(), equivalentSquares));
        assertEquals(5, matchingCount(RecognitionProfile.algebraicAc(), equivalentSquares));
    }

    @Test
    void broaderProfilesStillRejectNonEquivalentNearMisses() {
        List<Expr> nearMisses = List.of(
            parser.parseTerm("x^2 + 3*x*a + a^2"),
            parser.parseTerm("x^2 + 2*x*a + 2*a^2"),
            parser.parseTerm("x^2 + 2*x*a - a^2")
        );

        assertEquals(0, matchingCount(RecognitionProfile.exact(), nearMisses));
        assertEquals(0, matchingCount(RecognitionProfile.arithmeticAc(), nearMisses));
        assertEquals(0, matchingCount(RecognitionProfile.algebraicAc(), nearMisses));
    }

    private int matchingCount(RecognitionProfile profile, List<Expr> expressions) {
        PatternRewriteRule rule = completeSquareRule(profile);
        return (int) expressions.stream().filter(rule::matches).count();
    }

    private static PatternRewriteRule completeSquareRule(RecognitionProfile profile) {
        PatternExpr x = PatternExpr.var("X");
        PatternExpr a = PatternExpr.var("A");
        PatternExpr source = PatternExpr.op(ADD,
            PatternExpr.op(ADD,
                PatternExpr.op(POW, x, PatternExpr.num(2)),
                PatternExpr.op(MUL, PatternExpr.op(MUL, PatternExpr.num(2), x), a)),
            PatternExpr.op(POW, a, PatternExpr.num(2)));
        PatternExpr target = PatternExpr.op(POW,
            PatternExpr.op(ADD, x, a), PatternExpr.num(2));
        return new PatternRewriteRule("complete-square-coverage", source, target, profile);
    }
}
