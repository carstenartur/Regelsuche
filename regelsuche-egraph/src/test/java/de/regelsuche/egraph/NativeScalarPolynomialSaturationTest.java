package de.regelsuche.egraph;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.PolynomialNormalizer;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeScalarPolynomialSaturationTest {
    private static final ExpressionParser PARSER = new ExpressionParser();
    private static final PatternExpr X = PatternExpr.var("x"), Y = PatternExpr.var("y"), Z = PatternExpr.var("z");
    private static PatternExpr add(PatternExpr a, PatternExpr b) { return PatternExpr.op(BinaryOperator.ADD, a, b); }
    private static PatternExpr mul(PatternExpr a, PatternExpr b) { return PatternExpr.op(BinaryOperator.MUL, a, b); }
    private static final PatternRewriteRule FACTOR = new PatternRewriteRule("factor", add(mul(X, Y), mul(X, Z)), mul(X, add(Y, Z)));
    private static NativeScalarPolynomialSaturation.ScalarContext context(String... assumptions) {
        return new NativeScalarPolynomialSaturation.ScalarContext(List.of("a", "b", "c", "d"), List.of(assumptions));
    }
    private static NativeScalarPolynomialSaturation.Result run(String input, PatternRewriteRule rule) {
        return new EqualitySaturation(List.of(rule)).saturateNativeAc(PARSER.parseTerm(input), context(), NativeScalarPolynomialSaturation.Budget.defaults());
    }

    @Test void nativeConsumerFactorsANonAdjacentSubmultisetAndChecksTheActualPairsAndExtraction() {
        Expr source = PARSER.parseTerm("a*b+c+a*d");
        var graph = new EGraph(); var root = graph.addExpression(source);
        assertEquals(source, new EqualitySaturation(List.of(FACTOR)).saturate(graph, root, node -> 1).expression());

        var result = run("a*b+c+a*d", FACTOR);
        assertTrue(result.directUnions() > 0);
        assertTrue(result.requestedInventoryComplete());
        assertTrue(result.expression().isPresent());
        assertTrue(size(result.expression().orElseThrow()) < size(source));
        assertEquals(new PolynomialNormalizer().normalize(source), new PolynomialNormalizer().normalize(result.expression().orElseThrow()));
        assertEquals(NativeScalarPolynomialSaturation.CheckStatus.EQUAL, result.finalCheck().orElseThrow().status());
        assertTrue(result.applications().stream().filter(item -> item.unionApplied()).allMatch(item ->
            item.primitiveCheck().orElseThrow().status() == NativeScalarPolynomialSaturation.CheckStatus.EQUAL
                && item.contextCheck().orElseThrow().status() == NativeScalarPolynomialSaturation.CheckStatus.EQUAL));
        assertEquals("NOT_PRODUCED", result.formalProofDagStatus());
        assertEquals("UNAVAILABLE", result.totalWorkStatus());
        assertEquals(result.canonicalJson(), run("a*b+c+a*d", FACTOR).canonicalJson());
        assertTrue(result.work().counters().get("exactNormalizerDispatches") >= 4 * result.directUnions() + 2);
    }

    @Test void falseRuleMetadataCannotAuthorizeAnAcUnion() {
        var falseRule = new PatternRewriteRule("false-idempotence", add(X, X), X);
        var result = run("a+b+a", falseRule);
        assertEquals(0, result.directUnions());
        assertEquals(NativeScalarPolynomialSaturation.Outcome.REJECTED_REWRITE, result.outcome());
        assertTrue(result.applications().stream().anyMatch(item -> item.primitiveCheck().orElseThrow().status()
            == NativeScalarPolynomialSaturation.CheckStatus.NOT_EQUAL));
        assertEquals(PARSER.parseTerm("a+b+a"), result.expression().orElseThrow());
    }

    @Test void incompleteRoundNeverAppliesEvenAnEarlierCompleteRulesMatches() {
        var moreMatches = new PatternRewriteRule("many", add(X, Y), add(Y, X));
        var budget = new NativeScalarPolynomialSaturation.Budget(3, 512, 128,
            new ScalarPolynomialAcMatcher.Limits(8, 2, 200_000));
        var result = new EqualitySaturation(List.of(FACTOR, moreMatches)).saturateNativeAc(PARSER.parseTerm("a*b+c+a*d"), context(), budget);
        assertEquals(NativeScalarPolynomialSaturation.Outcome.MATCH_INCONCLUSIVE, result.outcome());
        assertFalse(result.rounds().getFirst().complete());
        assertTrue(result.rounds().getFirst().queries().getFirst().complete());
        assertFalse(result.rounds().getFirst().queries().getFirst().matches().isEmpty());
        assertEquals(0, result.directUnions());
        assertTrue(result.applications().isEmpty());
    }

    @Test void domainContextAndConditionalRulesAreFailClosedAndNeverSilentlyRebound() {
        var cancellation = new PatternRewriteRule("guarded-division", PatternExpr.op(BinaryOperator.DIV, mul(X, Y), X), Y);
        for (String fact : List.of("a != 0", "a = 0")) {
            var result = new EqualitySaturation(List.of(FACTOR, cancellation)).saturateNativeAc(PARSER.parseTerm("a*b+c+a*d"), context(fact), NativeScalarPolynomialSaturation.Budget.defaults());
            assertEquals(List.of(fact), result.context().assumptions());
            assertFalse(result.requestedInventoryComplete());
            assertTrue(result.rules().stream().anyMatch(item -> item.ruleId().equals("guarded-division") && !item.admitted()));
            assertEquals(result.contextHash(), result.finalCheck().orElseThrow().contextHash());
            assertTrue(result.applications().stream().allMatch(item -> item.primitiveCheck().orElseThrow().contextHash().equals(result.contextHash())));
        }
        for (String source : List.of("matmul(a,b)", "a/b", "a^0")) {
            var result = run(source, FACTOR);
            assertEquals(NativeScalarPolynomialSaturation.Outcome.UNSUPPORTED_SOURCE, result.outcome());
            assertTrue(result.expression().isEmpty()); assertEquals(0, result.directUnions());
        }
        for (var invalid : List.of(context("a is a matrix"), context("a = 0", "a != 0"),
                new NativeScalarPolynomialSaturation.ScalarContext(List.of("a"), List.of()))) {
            var result = new EqualitySaturation(List.of(FACTOR)).saturateNativeAc(PARSER.parseTerm("a*b"), invalid, NativeScalarPolynomialSaturation.Budget.defaults());
            assertTrue(result.expression().isEmpty()); assertEquals(0, result.directUnions());
        }
        var subclass = new PatternRewriteRule("guard-subclass", X, PatternExpr.num(0)) {
            @Override public boolean matches(Expr expression) { throw new AssertionError("guard bypassed"); }
        };
        assertFalse(run("a+b", subclass).rules().getFirst().admitted());
    }

    @Test void budgetPrefixAndNormalizerRefusalRemainInconclusive() {
        var zero = new NativeScalarPolynomialSaturation.Budget(2, 512, 128, new ScalarPolynomialAcMatcher.Limits(8, 32, 0));
        var stopped = new EqualitySaturation(List.of(FACTOR)).saturateNativeAc(PARSER.parseTerm("a*b+c+a*d"), context(), zero);
        assertEquals(NativeScalarPolynomialSaturation.Outcome.BUDGET_INCONCLUSIVE, stopped.outcome());
        assertEquals(0, stopped.work().chargedUnits());
        assertTrue(stopped.expression().isEmpty()); assertEquals(0, stopped.directUnions());

        // A legal bounded polynomial whose expansion exceeds the existing normalizer's term cap.
        String source = "(a+b+c+d+1)^8*(a+b+c+d+1)^8";
        var result = new EqualitySaturation(List.of()).saturateNativeAc(PARSER.parseTerm(source), context(), NativeScalarPolynomialSaturation.Budget.defaults());
        assertEquals(NativeScalarPolynomialSaturation.Outcome.CHECK_INCONCLUSIVE, result.outcome());
        assertEquals(NativeScalarPolynomialSaturation.CheckStatus.UNAVAILABLE, result.finalCheck().orElseThrow().status());
        assertTrue(result.expression().isEmpty());
    }

    @Test void actualCycleAfterValidRewriteIsNotReportedAsACompleteSearch() {
        var removeZero = new PatternRewriteRule("zero", add(PatternExpr.num(0), X), X);
        var result = run("a+0+b", removeZero);
        assertEquals(PARSER.parseTerm("a+b"), result.expression().orElseThrow());
        assertEquals(NativeScalarPolynomialSaturation.Outcome.MATCH_INCONCLUSIVE, result.outcome());
        assertTrue(result.rounds().stream().flatMap(round -> round.queries().stream())
            .anyMatch(query -> query.outcome() == ScalarPolynomialAcMatcher.Outcome.CYCLIC_INCONCLUSIVE));
        assertFalse(result.admittedFragmentFixedPoint());
    }

    @Test void repeatedVariableNearMissAndOrderedSubtractionDoNotCreateFalseCandidates() {
        var repeated = new PatternRewriteRule("double", add(X, X), mul(PatternExpr.num(2), X));
        assertEquals(0, run("a+b+c", repeated).directUnions());
        assertTrue(run("a+b+c", repeated).applications().isEmpty());
        var oriented = new PatternRewriteRule("subtract-zero", PatternExpr.op(BinaryOperator.SUB, X, PatternExpr.num(0)), X);
        assertEquals(0, run("0-a", oriented).directUnions());
        var unbound = new PatternRewriteRule("unbound", X, Y);
        assertFalse(run("a", unbound).rules().getFirst().admitted());
        var limited = new NativeScalarPolynomialSaturation.Budget(1, 512, 128, ScalarPolynomialAcMatcher.Limits.defaults());
        var result = new EqualitySaturation(List.of(FACTOR)).saturateNativeAc(PARSER.parseTerm("a*b+c+a*d"), context(), limited);
        assertEquals(NativeScalarPolynomialSaturation.Outcome.ROUND_LIMIT_INCONCLUSIVE, result.outcome());
        assertTrue(result.expression().isPresent());
        assertFalse(result.admittedFragmentFixedPoint());
    }

    @Test void finalCheckSharesTheConsumedBudgetAndRetainsItsCompletedHalfOnRefusal() {
        var engine = new EqualitySaturation(List.of());
        Expr input = PARSER.parseTerm("a+b");
        var baseline = engine.saturateNativeAc(input, context(), NativeScalarPolynomialSaturation.Budget.defaults());
        assertEquals(2, baseline.work().counters().get("exactNormalizerDispatches"));
        long limit = baseline.work().chargedUnits() - 1;
        var budget = new NativeScalarPolynomialSaturation.Budget(4, 512, 128, new ScalarPolynomialAcMatcher.Limits(8, 256, limit));
        var refused = engine.saturateNativeAc(input, context(), budget);
        assertEquals(NativeScalarPolynomialSaturation.Outcome.BUDGET_INCONCLUSIVE, refused.outcome());
        assertEquals(limit, refused.work().chargedUnits());
        assertEquals(1, refused.work().counters().get("exactNormalizerDispatches"));
        assertTrue(refused.finalCheck().orElseThrow().leftNormalForm().isPresent());
        assertTrue(refused.finalCheck().orElseThrow().rightNormalForm().isEmpty());
        assertTrue(refused.expression().isEmpty());
    }

    private static int size(Expr expression) {
        return expression instanceof de.regelsuche.ast.BinaryExpr binary ? 1 + size(binary.left()) + size(binary.right()) : 1;
    }
}
