package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scalar.ExactRational;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ExactMonomialInferenceTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void irrationalRootsDoNotBecomeApproximateExactBindings() {
        for (String value : List.of("2", "3", "5", "2 * x^2", "2 / 3")) {
            assertFalse(match(power(2), value).matched());
        }
        assertFalse(match(power(3), "2").matched());
    }

    @Test
    void exactIntegerRootsAndMonomialPowersStillMatch() {
        assertEquals("2", binding(power(2), "4"));
        assertEquals("3", binding(power(2), "9"));
        assertEquals("2", binding(power(3), "8"));
        assertEquals("2", binding(power(6), "64"));
        assertEquals("3 * x ^ 2", binding(power(2), "9 * x^4"));
    }

    @Test
    void rationalRootsRemainRationalInTheActualBinding() {
        assertEquals("2 / 3", binding(power(2), "4 / 9"));
        assertEquals("2 / 3 * x", binding(power(2), "(4 / 9) * x^2"));
        assertEquals("1.5", binding(power(2), "9 / 4"));
        assertEquals("0.1", binding(power(2), "0.01"));
    }

    @Test
    void rootBindingUsesExactReplayAfterFormatting() {
        var attempt = match(power(2), "4 / 9 * x^2");
        Expr original = attempt.bindings().get("A");
        Expr replayed = parser.parseTerm(ExpressionFormatter.format(original));
        assertTrue(EquivalenceAwarePatternMatcher.matchDetailed(power(2),
            parser.parseTerm("4 / 9 * x^2"), Map.of("A", replayed),
            RecognitionProfile.algebraicAc()).matched());
        assertEquals(new ExactRational(java.math.BigInteger.valueOf(2), java.math.BigInteger.valueOf(3)),
            BoundedExactMonomial.from(replayed, new BoundedExactMonomial.Budget())
                .orElseThrow().coefficient());
    }

    @Test
    void coefficientsCannotMatchByToleranceAfterMultiplicationOrDivision() {
        for (String source : List.of("1.0000000001 * 1", "1.0000000001 / 1", "1 / 1.0000000001")) {
            assertFalse(match(PatternExpr.num(1), source).matched());
        }
        assertFalse(match(PatternExpr.num(0), "0.0000000001 * 1").matched());
        assertTrue(match(PatternExpr.num(1), "3 / 3").matched());
    }

    @Test
    void decimalProductsUseExactRationalArithmeticInsteadOfBinaryRounding() {
        assertTrue(match(PatternExpr.num(0.02), "0.1 * 0.2").matched());
        assertFalse(match(PatternExpr.num(Math.nextUp(0.02)), "0.1 * 0.2").matched());
        assertTrue(match(PatternExpr.num(0.5), "1 / 2").matched());
    }

    @Test
    void nonzeroTinyDivisorsAreNotTreatedAsZero() {
        assertTrue(match(PatternExpr.num(10000000000.0), "1 / 0.0000000001").matched());
        assertFalse(match(PatternExpr.num(0), "1 / 0").matched());
    }

    @Test
    void symbolicCancellationRequiresAnAssumptionAwareBoundary() {
        for (String source : List.of("x / x", "(2*x) / (2*x)", "(x^2)/(x*x)")) {
            assertFalse(match(PatternExpr.num(1), source).matched());
        }
        assertFalse(match(PatternExpr.num(0), "0 / x").matched());
        assertFalse(match(PatternExpr.num(0), "0 * (1/x)").matched());
    }

    @Test
    void repeatedBindingsMustHaveIdenticalExactCoefficients() {
        var twice = PatternExpr.op(BinaryOperator.ADD, PatternExpr.var("A"), PatternExpr.var("A"));
        assertFalse(match(twice, "x + 1.0000000001*x").matched());
        assertTrue(match(twice, "2*x + x*2").matched());
        assertTrue(match(twice, "(2/3)*x + (4/6)*x").matched());
    }

    @Test
    void exactCompleteSquareRetainsFractionalAndStructuralCases() {
        PatternRewriteRule rule = completeSquare();
        for (String source : List.of("x^2 + a^2 + 2*a*x",
                "x^2 + 3*a*x + (9/4)*a^2", "x^2 + (4/3)*x*y + (4/9)*y^2",
                "x^2 + 2*x*sin(a) + sin(a)^2")) {
            assertTrue(rule.matches(parser.parseTerm(source)));
        }
        assertEquals("(x + 2 / 3 * y) ^ 2", ExpressionFormatter.format(
            rule.apply(parser.parseTerm("x^2 + (4/3)*x*y + (4/9)*y^2"))));
    }

    @Test
    void scaledSquareRetainsExactDecimalSyntaxForSearchSuccessors() {
        Expr source = parser.parseTerm("x*x + 3*x*a + 2.25*a*a");
        assertTrue(completeSquare().matches(source));
        assertEquals("(x + 1.5 * a) ^ 2",
            ExpressionFormatter.format(completeSquare().apply(source)));
        // A nonterminating rational still requires fraction syntax.
        assertEquals("2 / 3", binding(power(2), "4 / 9"));
    }

    @Test
    void completeSquareRejectsNearMissCoefficients() {
        for (String source : List.of("x^2 + 2.0000000001*x*y + y^2",
                "x^2 + 3*a*x + a^2", "x^2 + 2*a*y + a^2",
                "x^2 + 2*x*a + (1/0)*a^2")) {
            assertFalse(completeSquare().matches(parser.parseTerm(source)));
        }
    }

    @Test
    void nonFiniteCoefficientExpressionsDoNotBecomeBindings() {
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            var source = new BinaryExpr(new NumberExpr(value), BinaryOperator.MUL, new NumberExpr(1));
            assertFalse(EquivalenceAwarePatternMatcher.matchDetailed(power(2), source,
                Map.of(), RecognitionProfile.algebraicAc()).matched());
        }
    }

    @Test
    void oddNegativeRootIsExactButEvenNegativeRootIsNotAdmitted() {
        var negative = new NumberExpr(-8);
        var odd = EquivalenceAwarePatternMatcher.matchDetailed(power(3), negative,
            Map.of(), RecognitionProfile.algebraicAc());
        assertTrue(odd.matched());
        assertEquals(new NumberExpr(-2), odd.bindings().get("A"));
        assertFalse(EquivalenceAwarePatternMatcher.matchDetailed(power(2), negative,
            Map.of(), RecognitionProfile.algebraicAc()).matched());
    }

    @Test
    void zeroMonomialsNormalizeOnlyAfterDefinedChildrenWereChecked() {
        assertTrue(match(PatternExpr.num(0), "0*x").matched());
        assertEquals("0", binding(power(2), "0*x^3"));
        assertFalse(match(PatternExpr.num(0), "0*(1/0)").matched());
    }

    @Test
    void integerExponentOverflowIsInconclusiveRatherThanWrapped() {
        assertLimit("x^2147483647 * x", "ALGEBRAIC_EXPONENT_LIMIT");
        assertLimit("(x^1073741824)^2", "ALGEBRAIC_EXPONENT_LIMIT");
    }

    @Test
    void largeCoefficientPowersAreRejectedBeforeAllocation() {
        assertLimit("2^2147483647", "ALGEBRAIC_COEFFICIENT_LIMIT");
        assertLimit("(1/2)^5000", "ALGEBRAIC_COEFFICIENT_LIMIT");
    }

    @Test
    void unrepresentableExactRootIsNotRoundedBackIntoTheLegacyAst() {
        assertLimit("(3*3002399751580331) * (3*3002399751580331)",
            "ALGEBRAIC_BINDING_NOT_REPRESENTABLE");
    }

    @Test
    void deepInferenceIsBoundedAndPreservesCallerBindings() {
        Expr deep = new VariableExpr("x");
        for (int i = 0; i < 140; i++) {
            deep = new BinaryExpr(deep, BinaryOperator.MUL, new NumberExpr(1));
        }
        Map<String, Expr> bindings = new HashMap<>(Map.of("retained", new NumberExpr(7)));
        var result = EquivalenceAwarePatternMatcher.matchDetailed(power(2), deep,
            bindings, RecognitionProfile.algebraicAc());
        assertTrue(result.inconclusive());
        assertEquals("ALGEBRAIC_WORK_LIMIT", result.limitCode());
        assertEquals(Map.of("retained", new NumberExpr(7)), result.bindings());
        assertEquals(result.bindings(), bindings);
    }

    @Test
    void algebraicWorkBudgetIsSharedRatherThanResetPerSubexpression() {
        var budget = new BoundedExactMonomial.Budget();
        for (int i = 0; i < 10000; i++) {
            assertTrue(BoundedExactMonomial.from(new VariableExpr("x"), budget).isPresent());
        }
        var failure = assertThrows(BoundedExactMonomial.LimitExceeded.class,
            () -> BoundedExactMonomial.from(new VariableExpr("x"), budget));
        assertEquals("ALGEBRAIC_WORK_LIMIT", failure.code);
    }

    @Test
    void inferredBindingOrderIsDeterministic() {
        String source = "4*z^2*a^2";
        assertEquals("2 * a * z", binding(power(2), source));
        assertEquals(binding(power(2), source), binding(power(2), "4*a^2*z^2"));
    }

    @Test
    void nestedNegationCannotTurnArithmeticExhaustionIntoSuccess() {
        var matcher = ExprMatcher.not(ExprMatcher.pattern(power(2), RecognitionProfile.algebraicAc()));
        var result = matcher.match(parser.parseTerm("2^5000"));
        assertEquals(ExprMatcher.MatchStatus.INCONCLUSIVE, result.status());
        assertFalse(result.matched());
    }

    @Test
    void integerRootsAgreeWithAnExhaustiveMultiplicationReference() {
        for (int exponent = 2; exponent <= 7; exponent++) {
            for (int value = -256; value <= 256; value++) {
                ExactRational coefficient = ExactRational.integer(value);
                var root = new BoundedExactMonomial(coefficient, Map.of())
                    .exactRoot(exponent, new BoundedExactMonomial.Budget());
                int magnitude = referenceRoot(Math.abs(value), exponent);
                boolean exists = magnitude >= 0 && (value >= 0 || exponent % 2 == 1);
                assertEquals(exists, root.isPresent());
                if (exists) {
                    assertEquals(coefficient, root.orElseThrow().coefficient().pow(exponent));
                }
            }
        }
    }

    @Test
    void rationalRootsAgreeWithAnExhaustiveReducedFractionReference() {
        for (int numerator = 0; numerator <= 25; numerator++) {
            for (int denominator = 1; denominator <= 25; denominator++) {
                var coefficient = new ExactRational(java.math.BigInteger.valueOf(numerator),
                    java.math.BigInteger.valueOf(denominator));
                for (int exponent = 2; exponent <= 3; exponent++) {
                    var root = new BoundedExactMonomial(coefficient, Map.of())
                        .exactRoot(exponent, new BoundedExactMonomial.Budget());
                    boolean exists = referenceRoot(coefficient.numerator().intValueExact(), exponent) >= 0
                        && referenceRoot(coefficient.denominator().intValueExact(), exponent) >= 0;
                    assertEquals(exists, root.isPresent());
                    if (exists) {
                        assertEquals(coefficient, root.orElseThrow().coefficient().pow(exponent));
                    }
                }
            }
        }
    }

    private static int referenceRoot(int value, int exponent) {
        for (int candidate = 0; candidate <= value; candidate++) {
            long product = 1;
            for (int i = 0; i < exponent; i++) {
                product *= candidate;
            }
            if (product == value) {
                return candidate;
            }
            if (product > value) {
                return -1;
            }
        }
        return -1;
    }

    private EquivalenceAwarePatternMatcher.MatchAttempt match(PatternExpr pattern, String expression) {
        return EquivalenceAwarePatternMatcher.matchDetailed(pattern, parser.parseTerm(expression),
            Map.of(), RecognitionProfile.algebraicAc());
    }

    private String binding(PatternExpr pattern, String expression) {
        var result = match(pattern, expression);
        assertTrue(result.matched());
        return ExpressionFormatter.format(result.bindings().get("A"));
    }

    private void assertLimit(String expression, String code) {
        var result = match(power(2), expression);
        assertTrue(result.inconclusive());
        assertFalse(result.matched());
        assertEquals(code, result.limitCode());
        assertTrue(result.bindings().isEmpty());
    }

    private static PatternExpr power(int exponent) {
        return PatternExpr.op(BinaryOperator.POW, PatternExpr.var("A"), PatternExpr.num(exponent));
    }

    private static PatternRewriteRule completeSquare() {
        var x = PatternExpr.var("X");
        var a = PatternExpr.var("A");
        var source = PatternExpr.op(BinaryOperator.ADD,
            PatternExpr.op(BinaryOperator.ADD, PatternExpr.op(BinaryOperator.POW, x, PatternExpr.num(2)),
                PatternExpr.op(BinaryOperator.MUL, PatternExpr.op(BinaryOperator.MUL, PatternExpr.num(2), x), a)),
            PatternExpr.op(BinaryOperator.POW, a, PatternExpr.num(2)));
        return new PatternRewriteRule("complete-square", source,
            PatternExpr.op(BinaryOperator.POW, PatternExpr.op(BinaryOperator.ADD, x, a), PatternExpr.num(2)),
            RecognitionProfile.algebraicAc());
    }
}
