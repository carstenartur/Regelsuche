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
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.scalar.ExactRationalDomain;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Exact-AST regressions, including the former floating-point boundary cases. */
@Timeout(10)
class NumericBoundaryRegressionTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void ordinaryParserPreservesPreviouslyRejectedPrecisionAndHonorsLimits() {
        for (String source : List.of("9007199254740993", "9223372036854775807",
                "1.0000000000000001")) {
            assertEquals(source, ExpressionFormatter.format(parser.parseTerm(source)));
        }
        assertEquals("x = 9007199254740993", ExpressionFormatter.format(
            parser.parseEquation("x = 9007199254740993")));
        assertEquals(2, parser.parse(new InputRequest(
            InputType.SYSTEM, "x=1; y=9007199254740993")).equations().size());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> parser.parseTerm("0." + "0".repeat(400) + "1"));
        assertTrue(error.getMessage().contains("position 0"));
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
            ExpressionFormatter.format(NumberExpr.exact("100000000000000000000")));
        assertEquals("0.0000000001",
            ExpressionFormatter.format(NumberExpr.exact("0.00000000010")));
        for (double value : new double[] {Double.MIN_VALUE, Double.MIN_NORMAL,
                Double.MAX_VALUE, Math.nextUp(0x1.0p63)}) {
            String rendered = ExpressionFormatter.format(new NumberExpr(legacy(value)));
            assertFalse(rendered.contains("E"));
            assertFalse(rendered.contains("e"));
            assertEquals(legacy(value), numericValue(parser.parseTerm(rendered)));
        }
    }

    @Test
    void formatterRejectsNonFiniteNumbers() {
        for (double value : new double[] {Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                () -> NumberExpr.exact(Double.toString(value)));
        }
    }

    @Test
    void formatterPreservesNegativePowerBaseParentheses() {
        Expr expression = new BinaryExpr(NumberExpr.exact("-100000000000000000000"),
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
                    PatternExpr.num(legacy(expected)), new NumberExpr(legacy(expected)),
                    Map.of(), profile).matched());
                assertFalse(EquivalenceAwarePatternMatcher.matchDetailed(
                    PatternExpr.num(legacy(expected)), new NumberExpr(legacy(Math.nextUp(expected))),
                    Map.of(), profile).matched());
            }
        }
    }

    @Test
    void nonFiniteLiteralsCannotEnterAnExactPattern() {
        for (String value : List.of("NaN", "Infinity", "-Infinity")) {
            assertThrows(IllegalArgumentException.class, () -> PatternExpr.num(value));
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
                    BinaryOperator.POW, new NumberExpr(legacy(exponent))), Map.of(),
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
        Expr parsed = parser.parseTerm(ExpressionFormatter.format(new NumberExpr(legacy(value))));
        assertEquals(legacy(value), numericValue(parsed));
    }

    private static ExactRational numericValue(Expr expression) {
        if (expression instanceof NumberExpr number) {
            return number.value();
        }
        BinaryExpr binary = (BinaryExpr) expression;
        return switch (binary.operator()) {
            case SUB -> numericValue(binary.left()).subtract(numericValue(binary.right()));
            case DIV -> numericValue(binary.left()).divide(numericValue(binary.right()));
            default -> throw new AssertionError("Unexpected numeric syntax: " + expression);
        };
    }

    private static ExactRational legacy(double value) {
        return ExactRationalDomain.legacyDecimalValue(value).orElseThrow();
    }

    private static PatternExpr powerPattern(double exponent) {
        return PatternExpr.op(BinaryOperator.POW, PatternExpr.var("A"),
            PatternExpr.num(legacy(exponent)));
    }
}
