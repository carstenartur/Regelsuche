package de.regelsuche.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.value.ExprValueFactory;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactNumericAstTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void ordinarySyntaxPreservesIntegersBeyondBinaryPrecision() {
        assertEquals("9007199254740993", ExpressionFormatter.format(
            parser.parseTerm("9007199254740993")));
        assertEquals("1.0000000000000001", ExpressionFormatter.format(
            parser.parseTerm("1.0000000000000001")));
    }

    @Test
    void exactValueIdentityDoesNotDependOnRetainingSourceEvidence() {
        var first = parser.parseExactTerm("9007199254740992").expression();
        var second = parser.parseExactTerm("9007199254740993").expression();
        assertNotEquals(first, second);
        try (var values = new ExprValueFactory()) {
            assertNotEquals(values.fromExpr(first), values.fromExpr(second));
        }
    }

    @Test
    void primitiveNumericRewriteDoesNotRoundNewIntegers() {
        var engine = new AstRewriteTransformationEngine();
        assertTrue(engine.transform("9007199254740992 + 1").stream()
            .anyMatch(move -> move.rule().equals("ast_fold_numeric_arithmetic")
                && move.transformedExpression().equals("9007199254740993")));
    }

    @Test
    void primitiveNumericRewriteUsesExactDecimalArithmetic() {
        var engine = new AstRewriteTransformationEngine();
        assertTrue(engine.transform("0.1 + 0.2").stream()
            .anyMatch(move -> move.rule().equals("ast_fold_numeric_arithmetic")
                && move.transformedExpression().equals("0.3")));
    }

    @Test
    void rationalLeavesRetainValueIdentityThroughSyntaxAndJson() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String scalar : List.of("1/3", "-2/7", "9007199254740993", "1.0000000000000001")) {
            NumberExpr number = NumberExpr.exact(scalar);
            String json = mapper.writeValueAsString(number);
            assertEquals(number, mapper.readValue(json, NumberExpr.class));
            assertEquals(number.value().canonicalText(), mapper.readTree(json).path("value").textValue());
            try (var values = new ExprValueFactory()) {
                for (var expression : List.of(number,
                        new BinaryExpr(new VariableExpr("x"), BinaryOperator.MUL, number),
                        new BinaryExpr(number, BinaryOperator.POW, new NumberExpr(2)))) {
                    assertEquals(values.fromExpr(expression), values.fromExpr(
                        parser.parseTerm(ExpressionFormatter.format(expression))));
                }
            }
        }
    }

    @Test
    void independentlyCreatedNumericPatternsUseValueEqualityAndReplayExactly() {
        var literal = PatternExpr.num("9007199254740993");
        assertTrue(literal.match(parser.parseTerm("9007199254740993"), new HashMap<>()));
        assertFalse(literal.match(parser.parseTerm("9007199254740992"), new HashMap<>()));
        var matcher = ExprMatcher.literalNumber("9007199254740993");
        assertTrue(matcher.match(parser.parseTerm("9007199254740993")).matched());
        assertFalse(matcher.match(parser.parseTerm("9007199254740992")).matched());
        assertTrue(ExprMatcher.literalNumber("1/3").match(NumberExpr.exact("1/3")).matched());
        var rule = new PatternRewriteRule("exact-large-add-zero",
            PatternExpr.op(BinaryOperator.ADD, PatternExpr.var("A"), PatternExpr.num(0)), PatternExpr.var("A"));
        var source = parser.parseTerm("9007199254740993 + 0");
        assertEquals(NumberExpr.exact("9007199254740993"), rule.apply(source));
        assertEquals(rule.apply(source), rule.apply(parser.parseTerm(ExpressionFormatter.format(source))));
    }

    @Test
    void fractionsAreComputedExactlyAndZeroDivisionIsRetained() {
        var fold = new AstRewriteTransformationEngine().rules().stream()
            .filter(rule -> rule.id().equals("ast_fold_numeric_arithmetic")).findFirst().orElseThrow();
        var third = fold.apply(parser.parseTerm("1 / 3"));
        var twoThirds = fold.apply(new BinaryExpr(third, BinaryOperator.ADD, third));
        assertEquals(new NumberExpr(1), fold.apply(new BinaryExpr(twoThirds, BinaryOperator.ADD, third)));
        assertFalse(fold.matches(parser.parseTerm("1 / 0")));
        try (var values = new ExprValueFactory()) {
            assertNotEquals(values.fromExpr(parser.parseTerm("0 / 0")), values.number(0));
        }
    }

    @Test
    void canonicalKeysDistinguishExactValuesFromTheirNearestLegacyValues() {
        var canonicalizer = new ExpressionCanonicalizer();
        assertNotEquals(canonicalizer.stableHash("9007199254740993 * sin(x)"),
            canonicalizer.stableHash("9007199254740992 * sin(x)"));
        assertEquals("9007199254740993", canonicalizer.canonicalize("9007199254740992 + 1"));
        assertEquals("1", canonicalizer.canonicalize("1/3 + 1/3 + 1/3"));
    }

    @Test
    void integerNarrowingAndIrrationalRootsRemainExplicitFailures() {
        assertThrows(ArithmeticException.class, () -> ExactRational.parse("2147483648").intValueExact());
        assertThrows(ArithmeticException.class, () -> ExactRational.parse("1/2").longValueExact());
        assertEquals(ExactRational.parse("2/3"), ExactRational.parse("4/9").sqrtExact().orElseThrow());
        assertTrue(ExactRational.integer(2).sqrtExact().isEmpty());
    }
}
