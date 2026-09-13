package de.regelsuche.egraph;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeScalarAdmissionReviewTest {
    private static final PatternExpr X = PatternExpr.var("x");
    private static final NativeScalarPolynomialSaturation.ScalarContext CONTEXT =
        new NativeScalarPolynomialSaturation.ScalarContext(List.of("a"), List.of());

    @Test void deepRequestedPatternsAreAdmittedBeforeAnyRecursiveFingerprint() {
        PatternExpr deep = X;
        for (int depth = 0; depth < 5000; depth++) deep = PatternExpr.op(BinaryOperator.ADD, deep, X);
        var sourceRule = new PatternRewriteRule("deep-source", deep, X);
        var targetRule = new PatternRewriteRule("deep-target", X, deep);
        for (var rule : List.of(sourceRule, targetRule)) {
            var result = assertDoesNotThrow(() -> new EqualitySaturation(List.of(rule)).saturateNativeAc(
                new ExpressionParser().parseTerm("a"), CONTEXT, NativeScalarPolynomialSaturation.Budget.defaults()));
            assertEquals(0, result.directUnions());
            assertFalse(result.requestedInventoryComplete());
            assertFalse(result.rules().getFirst().admitted());
            assertNull(result.rules().getFirst().ruleHash());
            assertTrue(result.canonicalJson().contains("INCOMPLETE_BEFORE_RULE_ADMISSION"));
        }
    }

    @Test void wideAndSharedPatternsRetainMissingRuleHashesInsteadOfHashingExcludedMaterial() {
        PatternExpr shared = X;
        for (int depth = 0; depth < 7; depth++) shared = PatternExpr.op(BinaryOperator.ADD, shared, shared);
        for (PatternExpr pattern : List.of(shared, new PatternExpr.Function("f", java.util.Collections.nCopies(65, X)),
                PatternExpr.var("x".repeat(129)))) {
            var rule = new PatternRewriteRule("excluded-material", pattern, X);
            var result = new EqualitySaturation(List.of(rule)).saturateNativeAc(new NumberExpr(1), CONTEXT,
                NativeScalarPolynomialSaturation.Budget.defaults());
            assertFalse(result.requestedInventoryComplete());
            assertNull(result.rules().getFirst().ruleHash());
            assertTrue(result.canonicalJson().contains("UNAVAILABLE_BEFORE_RULE_ADMISSION"));
            assertEquals(0, result.directUnions());
        }
    }

    @Test void exhaustedAuthorityCannotHashADeepRequestedPatternFirst() {
        PatternExpr deep = X;
        for (int depth = 0; depth < 5000; depth++) deep = PatternExpr.op(BinaryOperator.ADD, deep, X);
        var rule = new PatternRewriteRule("deep-zero-budget", deep, X);
        var budget = new NativeScalarPolynomialSaturation.Budget(1, 64, 64,
            new ScalarPolynomialAcMatcher.Limits(8, 64, 0));
        var result = assertDoesNotThrow(() -> new EqualitySaturation(List.of(rule)).saturateNativeAc(
            new NumberExpr(1), CONTEXT, budget));
        assertEquals(NativeScalarPolynomialSaturation.Outcome.BUDGET_INCONCLUSIVE, result.outcome());
        assertEquals(0, result.work().chargedUnits());
        assertEquals(0, result.directUnions());
        assertTrue(result.expression().isEmpty());
    }

    @Test void distinctPlaceholderCodeUnitsRemainDistinctInAdmittedPlanAndRunIdentities() {
        var plans = new java.util.HashSet<String>();
        var runs = new java.util.HashSet<String>();
        for (String name : List.of("\uD800", "\uD801", "\uDC00", "\uD83D\uDE00", "\\uD800")) {
            var placeholder = PatternExpr.var(name);
            var plan = ScalarPolynomialAcMatcher.compile(placeholder);
            assertTrue(plans.add(plan.contentHash()), "different exact Java names must issue different plan identities");
            var rule = new PatternRewriteRule("zero", PatternExpr.op(BinaryOperator.ADD, placeholder, PatternExpr.num(0)), placeholder);
            var result = new EqualitySaturation(List.of(rule)).saturateNativeAc(
                new ExpressionParser().parseTerm("a+0"), CONTEXT, NativeScalarPolynomialSaturation.Budget.defaults());
            assertTrue(result.requestedInventoryComplete());
            assertEquals(1, result.directUnions());
            assertTrue(runs.add(result.contentHash()), "actual admitted runs must retain those exact names");
            assertEquals(plan.canonicalJson(), new String(plan.canonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test void oversizedExactCoefficientsAreRejectedBeforeDecimalReceiptMaterialization() {
        var number = new UnrenderableOversizedInteger();
        var scalar = ExactRational.integer(number);
        assertSame(number, scalar.numerator(), "the immutable numeric probe must survive exact scalar construction");
        var literal = PatternExpr.num(scalar);
        for (var rule : List.of(new PatternRewriteRule("large-source", literal, X),
                new PatternRewriteRule("large-target", X, literal))) {
            var result = assertDoesNotThrow(() -> new EqualitySaturation(List.of(rule)).saturateNativeAc(
                new NumberExpr(1), CONTEXT, NativeScalarPolynomialSaturation.Budget.defaults()));
            assertFalse(result.rules().getFirst().admitted());
            assertEquals(0, result.directUnions());
        }
        var source = assertDoesNotThrow(() -> new EqualitySaturation(List.of()).saturateNativeAc(
            new NumberExpr(scalar), CONTEXT, NativeScalarPolynomialSaturation.Budget.defaults()));
        assertEquals(NativeScalarPolynomialSaturation.Outcome.UNSUPPORTED_SOURCE, source.outcome());
        assertTrue(source.expression().isEmpty());
    }

    private static final class UnrenderableOversizedInteger extends BigInteger {
        private static final long serialVersionUID = 1L;
        private UnrenderableOversizedInteger() { super(BigInteger.ONE.shiftLeft(1024).toByteArray()); }
        @Override public BigInteger divide(BigInteger divisor) { return divisor.equals(BigInteger.ONE) ? this : super.divide(divisor); }
        @Override public String toString() { throw new AssertionError("oversized coefficient rendered before admission"); }
    }
}
