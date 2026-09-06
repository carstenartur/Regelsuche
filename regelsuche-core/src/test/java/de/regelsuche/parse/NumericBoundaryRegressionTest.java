package de.regelsuche.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.transform.EquivalenceAwarePatternMatcher;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.value.ExprValueFactory;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Safety regressions for the numeric boundary pending the exact-AST migration. */
@Timeout(10)
class NumericBoundaryRegressionTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void ordinaryParserRejectsDecimalInformationLoss() {
        for (String source : List.of(
                "9007199254740993", "9223372036854775807",
                "1.0000000000000001", "0." + "0".repeat(400) + "1")) {
            IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> parser.parseTerm(source));
            assertTrue(error.getMessage().contains("position 0"));
        }
        assertThrows(IllegalArgumentException.class,
            () -> parser.parseEquation("x = 9007199254740993"));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse(new InputRequest(
                InputType.SYSTEM, "x=1; y=9007199254740993")));
    }

    @Test
    void ordinaryParserKeepsTheFiniteDecimalConvention() {
        for (String source : List.of(
                "0", "000001.000", "0.1", "999999999999999",
                "9007199254740992", "100000000000000000000",
                "0.0000000001", "2.0000000001")) {
            String rendered = ExpressionFormatter.format(parser.parseTerm(source));
            assertEquals(0, new BigDecimal(source).compareTo(new BigDecimal(rendered)));
        }
    }

    @Test
    void exactParserStillRetainsDistinctSourceValues() {
        ExactParsedTerm first = parser.parseExactTerm("9007199254740992");
        ExactParsedTerm second = parser.parseExactTerm("9007199254740993");
        assertEquals("9007199254740992",
            first.literals().getFirst().exactValue().canonicalText());
        assertEquals("9007199254740993",
            second.literals().getFirst().exactValue().canonicalText());
        assertNotEquals(first.literals().getFirst().exactValue(),
            second.literals().getFirst().exactValue());
        assertEquals("10000000000000001/10000000000000000",
            parser.parseExactTerm("1.0000000000000001")
                .literals().getFirst().exactValue().canonicalText());
    }

    @Test
    void formatterDoesNotNarrowLargeIntegersOrEmitExponentSyntax() {
        assertEquals("100000000000000000000",
            ExpressionFormatter.format(new NumberExpr(1.0e20)));
        assertEquals("0.0000000001",
            ExpressionFormatter.format(new NumberExpr(1.0e-10)));
        for (double value : new double[] {Double.MIN_VALUE, Double.MIN_NORMAL,
                Double.MAX_VALUE, Math.nextUp(0x1.0p63)}) {
            String rendered = ExpressionFormatter.format(new NumberExpr(value));
            assertFalse(rendered.contains("E"));
            assertFalse(rendered.contains("e"));
            assertEquals(0, BigDecimal.valueOf(value).compareTo(new BigDecimal(rendered)));
        }
    }

    @Test
    void formatterRejectsNonFiniteNumbers() {
        for (double value : new double[] {Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                () -> ExpressionFormatter.format(new NumberExpr(value)));
        }
    }

    @Test
    void formatterPreservesNegativePowerBaseParentheses() {
        Expr expression = new BinaryExpr(new NumberExpr(-1.0e20),
            BinaryOperator.POW, new NumberExpr(2));
        assertEquals("(-100000000000000000000) ^ 2",
            ExpressionFormatter.format(expression));
    }

    @Test
    void deterministicFiniteBitPatternsRoundTripThroughTheOrdinaryParser() {
        Random random = new Random(661);
        for (int index = 0; index < 2048; index++) {
            double value = Double.longBitsToDouble(random.nextLong());
            if (Double.isFinite(value)) {
                assertRoundTrip(value);
            }
        }
        for (double value : new double[] {0.0, -0.0, Double.MIN_VALUE,
                -Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
                Math.nextDown(1.0), Math.nextUp(1.0), 0x1.0p53, 0x1.0p63}) {
            assertRoundTrip(value);
        }
    }

    @Test
    void directLiteralConstraintsDoNotUseToleranceInAnyProfile() {
        for (RecognitionProfile profile : List.of(RecognitionProfile.exact(),
                RecognitionProfile.arithmeticAc(), RecognitionProfile.algebraicAc())) {
            for (double expected : new double[] {0.0, 1.0, 2.0, 0x1.0p53}) {
                assertTrue(EquivalenceAwarePatternMatcher.matchDetailed(
                    PatternExpr.num(expected), new NumberExpr(expected),
                    Map.of(), profile).matched());
                assertFalse(EquivalenceAwarePatternMatcher.matchDetailed(
                    PatternExpr.num(expected), new NumberExpr(Math.nextUp(expected)),
                    Map.of(), profile).matched());
            }
        }
    }

    @Test
    void nonFiniteLiteralsCannotMatchThemselves() {
        for (double value : new double[] {Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertFalse(EquivalenceAwarePatternMatcher.matchDetailed(
                PatternExpr.num(value), new NumberExpr(value), Map.of(),
                RecognitionProfile.exact()).matched());
        }
    }

    @Test
    void addZeroRuleRejectsNearZeroAndPreservesReplayAndValueIdentity() {
        PatternRewriteRule rule = new PatternRewriteRule("numeric-boundary-add-zero",
            PatternExpr.op(BinaryOperator.ADD, PatternExpr.var("A"), PatternExpr.num(0)),
            PatternExpr.var("A"));
        Expr nearMiss = parser.parseTerm("1 + 0.0000000001");
        assertFalse(rule.matches(nearMiss));
        assertThrows(IllegalArgumentException.class, () -> rule.apply(nearMiss));
        Expr source = parser.parseTerm("9007199254740992 + 0");
        assertTrue(rule.matches(source));
        Expr result = rule.apply(source);
        assertEquals("9007199254740992", ExpressionFormatter.format(result));
        Expr replay = rule.apply(parser.parseTerm(ExpressionFormatter.format(source)));
        assertEquals(result, replay);
        try (ExprValueFactory values = new ExprValueFactory()) {
            assertEquals(values.fromExpr(result).key(), values.fromExpr(replay).key());
        }
    }

    @Test
    void inferredPowersDoNotRoundFractionalExponents() {
        for (double exponent : new double[] {Math.nextDown(2.0), Math.nextUp(2.0),
                2.0000000001}) {
            assertFalse(EquivalenceAwarePatternMatcher.matchDetailed(
                powerPattern(exponent), parser.parseTerm("x * x"), Map.of(),
                RecognitionProfile.algebraicAc()).matched());
            assertFalse(EquivalenceAwarePatternMatcher.matchDetailed(
                powerPattern(2), new BinaryExpr(new VariableExpr("x"),
                    BinaryOperator.POW, new NumberExpr(exponent)), Map.of(),
                RecognitionProfile.algebraicAc()).matched());
        }
        assertTrue(EquivalenceAwarePatternMatcher.matchDetailed(
            powerPattern(2), parser.parseTerm("x * x"), Map.of(),
            RecognitionProfile.algebraicAc()).matched());
    }

    @Test
    void inferredIntegerExponentDoesNotSaturateAtTheIntBoundary() {
        assertFalse(EquivalenceAwarePatternMatcher.matchDetailed(
            powerPattern(2147483648.0), parser.parseTerm("x ^ 2147483647"),
            Map.of(), RecognitionProfile.algebraicAc()).matched());
    }

    @Test
    void boundedMatchingRetainsInconclusiveStatusAndCallerBindings() {
        PatternExpr pattern = PatternExpr.op(BinaryOperator.ADD,
            PatternExpr.var("A"), PatternExpr.var("B"));
        Map<String, Expr> bindings = new HashMap<>(Map.of("retained", new NumberExpr(7)));
        Map<String, Expr> original = Map.copyOf(bindings);
        var limited = EquivalenceAwarePatternMatcher.matchDetailed(pattern,
            parser.parseTerm("x + y"), bindings, RecognitionProfile.arithmeticAc(), 1);
        assertTrue(limited.inconclusive());
        assertEquals("COMMUTATIVE_BACKTRACKING_LIMIT", limited.limitCode());
        assertEquals(original, limited.bindings());
        assertEquals(original, bindings);
        assertTrue(EquivalenceAwarePatternMatcher.matchDetailed(pattern,
            parser.parseTerm("x + y"), bindings, RecognitionProfile.arithmeticAc(), 10).matched());
        assertEquals(original, bindings);
    }

    private void assertRoundTrip(double value) {
        Expr parsed = parser.parseTerm(ExpressionFormatter.format(new NumberExpr(value)));
        double actual;
        if (parsed instanceof NumberExpr number) {
            actual = number.value();
        } else {
            BinaryExpr negative = (BinaryExpr) parsed;
            assertEquals(BinaryOperator.SUB, negative.operator());
            assertEquals(new NumberExpr(0), negative.left());
            actual = -((NumberExpr) negative.right()).value();
        }
        assertEquals(Double.doubleToLongBits(value == 0.0 ? 0.0 : value),
            Double.doubleToLongBits(actual));
    }

    private static PatternExpr powerPattern(double exponent) {
        return PatternExpr.op(BinaryOperator.POW, PatternExpr.var("A"),
            PatternExpr.num(exponent));
    }
}
