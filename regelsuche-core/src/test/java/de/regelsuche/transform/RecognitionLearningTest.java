package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static de.regelsuche.ast.BinaryOperator.MUL;
import static de.regelsuche.ast.BinaryOperator.POW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecognitionLearningTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void learnsNarrowestSafeProfileFromExamples() {
        PatternExpr pattern = completeSquarePattern();
        RecognitionProfile learned = new RecognitionProfileLearner().learn(
            pattern,
            List.of(
                parser.parseTerm("x^2 + 2*x*a + a^2"),
                parser.parseTerm("x^2 + a^2 + 2*a*x")
            ),
            List.of(parser.parseTerm("x^2 + 3*x*a + a^2"))
        );
        assertEquals(RecognitionProfile.arithmeticAc(), learned);
    }

    @Test
    void learnsAlgebraicProfileWhenPowerProductAndScaledBindingsAreRequired() {
        PatternExpr pattern = completeSquarePattern();
        RecognitionProfile learned = new RecognitionProfileLearner().learn(
            pattern,
            List.of(
                parser.parseTerm("x*x + 3*x*a + 2.25*a*a"),
                parser.parseTerm("9*a^2/4 + 3*a*x + x^2")
            ),
            List.of(parser.parseTerm("x^2 + 3*x*a + a^2"))
        );
        assertEquals(RecognitionProfile.algebraicAc(), learned);
        PatternRewriteRule rule = new PatternRewriteRule("square", pattern,
            PatternExpr.op(POW, PatternExpr.op(ADD, PatternExpr.var("X"), PatternExpr.var("A")), PatternExpr.num(2)),
            learned);
        assertTrue(rule.matches(parser.parseTerm("x*x + 3*x*a + 2.25*a*a")));
        assertFalse(rule.matches(parser.parseTerm("x^2 + 3*x*a + a^2")));
    }

    @Test
    void persistsRecognitionProfileWithoutLosingMeaning() {
        RecognitionProfile original = RecognitionProfile.algebraicAc()
            .withRecognitionRules(Set.of("learned-factor", "safe-power"), 2);
        RecognitionProfileData data = RecognitionProfileData.from(original);
        assertEquals(RecognitionProfileData.SCHEMA, data.schema());
        assertEquals(original, data.toProfile());
    }

    @Test
    void antiUnifiesEquivalentRepresentations() {
        PatternExpr generalized = new EquivalenceAwareAntiUnifier().generalize(
            List.of(
                parser.parseTerm("x*x + 2*x*y + y*y"),
                parser.parseTerm("b^2 + 2*a*b + a^2")
            ),
            RecognitionProfile.algebraicAc()
        );
        assertTrue(EquivalenceAwarePatternMatcher.match(generalized,
            parser.parseTerm("m^2 + 2*m*n + n^2"), new java.util.HashMap<>(),
            RecognitionProfile.algebraicAc()));
    }

    @Test
    void usesAllowListedLearnedRuleAsBoundedRecognitionTheory() {
        PatternExpr x = PatternExpr.var("X");
        PatternRewriteRule productToPower = new PatternRewriteRule(
            "safe-power",
            PatternExpr.op(MUL, x, x),
            PatternExpr.op(POW, x, PatternExpr.num(2)),
            RecognitionProfile.arithmeticAc()
        );
        RecognitionProfile profile = RecognitionProfile.exact()
            .withRecognitionRules(Set.of("safe-power"), 1);
        EquivalenceClassPatternMatcher.MatchResult result = new EquivalenceClassPatternMatcher().match(
            PatternExpr.op(POW, PatternExpr.var("A"), PatternExpr.num(2)),
            parser.parseTerm("z*z"),
            profile,
            new RecognitionTheory(List.of(productToPower))
        );
        assertTrue(result.matched());
        assertEquals(parser.parseTerm("z^2"), result.representative());
    }

    private static PatternExpr completeSquarePattern() {
        PatternExpr x = PatternExpr.var("X");
        PatternExpr a = PatternExpr.var("A");
        PatternExpr squareX = PatternExpr.op(POW, x, PatternExpr.num(2));
        PatternExpr middle = PatternExpr.op(MUL,
            PatternExpr.op(MUL, PatternExpr.num(2), x), a);
        PatternExpr squareA = PatternExpr.op(POW, a, PatternExpr.num(2));
        return PatternExpr.op(ADD, PatternExpr.op(ADD, squareX, middle), squareA);
    }
}
