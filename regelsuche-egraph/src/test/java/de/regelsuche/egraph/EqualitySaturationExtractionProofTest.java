package de.regelsuche.egraph;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.egraph.proof.ExtractionProofBuilder;
import de.regelsuche.egraph.proof.ExtractionProofChecker;
import de.regelsuche.egraph.proof.ProofLimits;
import de.regelsuche.egraph.proof.ProofOutcome;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EqualitySaturationExtractionProofTest {
    private static final Expr X = new VariableExpr("x");
    private static final PatternExpr A = new PatternExpr.Placeholder("A");

    @Test
    void childRewriteProducesDetachedCongruenceCertificate() {
        Expr input = fn("parent", fn("f", X));
        Expr expected = fn("parent", X);
        List<RewriteRule> rules = List.of(rule("erase-f", pattern("f", A), A));

        var result = run(input, rules);

        assertEquals(expected, result.expression());
        assertDetached(input, expected, rules, result);
        assertTrue(result.proof().checked().orElseThrow().certificate().steps().stream()
            .anyMatch(step -> step instanceof de.regelsuche.egraph.proof.ExtractionCertificate.Congruence));
    }

    @Test
    void revisitsOuterRuleAfterAnInnerRewriteRevealsItsLiteral() {
        var parser = new ExpressionParser();
        var input = parser.parseTerm("(x + 0) * 0 + y");
        var expected = parser.parseTerm("y");
        var ids = List.of("ast_add_zero_right", "ast_add_zero_left", "ast_multiply_one_right",
            "ast_multiply_one_left", "ast_multiply_zero_right", "ast_multiply_zero_left",
            "ast_subtract_zero", "ast_product_to_power_two");
        var rules = AstRewriteTransformationEngine.allBuiltInRules().stream()
            .filter(rule -> ids.contains(rule.id())).toList();
        for (boolean dirtyWorklist : List.of(true, false)) {
            var result = new EqualitySaturation(rules, new EqualitySaturation.Config(8, 256, dirtyWorklist))
                .saturateWithProof(input, node -> node.isLeaf() ? 0 : 1, EqualitySaturation.ProofOptions.defaults());
            assertEquals(expected, result.expression(), "dirty worklist: " + dirtyWorklist);
            assertDetached(input, expected, rules, result);
        }
    }

    @Test
    void dirtyWorklistRevisitsGrandparentsOfAChangedClass() {
        Expr input = fn("top", fn("mid", X, fn("erase", new VariableExpr("y"))));
        List<RewriteRule> rules = List.of(
            rule("top-zero", pattern("top", pattern("mid", A, PatternExpr.num(0))), A),
            rule("erase", pattern("erase", A), PatternExpr.num(0)));
        for (boolean dirtyWorklist : List.of(true, false)) {
            var result = new EqualitySaturation(rules, new EqualitySaturation.Config(8, 256, dirtyWorklist))
                .saturateWithProof(input, node -> node.isLeaf() ? 0 : 1, EqualitySaturation.ProofOptions.defaults());
            assertEquals(X, result.expression(), "dirty worklist: " + dirtyWorklist);
            assertDetached(input, X, rules, result);
        }
    }

    @Test
    void nativeRepeatedPlaceholderNeedsCongruenceBridge() {
        Expr input = fn("g", fn("a"), fn("b"));
        List<RewriteRule> rules = List.of(
            rule("a-b", pattern("a"), pattern("b")),
            rule("same", pattern("g", A, A), A));

        var result = run(input, rules);

        assertEquals(fn("a"), result.expression());
        assertDetached(input, fn("a"), rules, result);
    }

    @Test
    void extractionCombinesIndependentChildImprovements() {
        Expr y = new VariableExpr("y");
        Expr input = new BinaryExpr(fn("f", X), BinaryOperator.ADD, fn("g", y));
        Expr expected = new BinaryExpr(X, BinaryOperator.ADD, y);
        List<RewriteRule> rules = List.of(rule("erase-f", pattern("f", A), A),
            rule("erase-g", pattern("g", A), A));

        var result = run(input, rules);

        assertEquals(expected, result.expression());
        assertDetached(input, expected, rules, result);
    }

    @Test
    void proofModeNeverInvokesLegacyNativeWitness() {
        var result = run(fn("f", X), List.of(rule("erase", pattern("f", A), A)));

        var provenance = result.diagnosticResult().orElseThrow().provenance();
        assertTrue(provenance.hasExtractionProof());
        assertTrue(provenance.witnesses().isEmpty());
        assertEquals(0, provenance.nativeWitnessExtractions());
        assertEquals(1, provenance.droppedWitnesses());
    }

    @Test
    void checkedTokenCannotBeAttachedToDifferentOutput() {
        var result = run(fn("f", X), List.of(rule("erase", pattern("f", A), A)));
        var diagnostic = result.diagnosticResult().orElseThrow();
        Expr unrelated = new VariableExpr("c");

        assertThrows(IllegalArgumentException.class, () -> new EqualitySaturation.ProvedResult(
            unrelated, result.diagnosticResult(), result.proof()));
        assertThrows(IllegalArgumentException.class, () -> new EqualitySaturation.Result(
            unrelated, diagnostic.stats(), diagnostic.diagnostics(), diagnostic.provenance()));
    }

    @Test
    void replayExhaustionKeepsExactOriginalInput() {
        Expr input = fn("f", X);
        var zeroReplay = new ProofLimits(50_000, 100_000, 500_000, 1_000_000,
            4096, 256, 100_000, 0);
        var result = new EqualitySaturation(List.of(rule("erase", pattern("f", A), A)))
            .saturateWithProof(input, node -> 1,
                new EqualitySaturation.ProofOptions(ProofLimits.defaults(), zeroReplay));

        assertSame(input, result.expression());
        assertEquals(ProofOutcome.Status.LIMIT_EXCEEDED, result.proof().status());
        assertFalse(result.diagnosticResult().orElseThrow().provenance().hasExtractionProof());
    }

    @Test
    void moreThan256EssentialUnionsRemainProvable() {
        List<RewriteRule> rules = new ArrayList<>();
        for (int i = 0; i < 260; i++) {
            rules.add(rule("link-" + i, pattern("f" + i, A), pattern("f" + (i + 1), A)));
        }
        rules.add(rule("last", pattern("f260", A), A));
        Expr input = fn("f0", X);

        var result = run(input, rules);

        assertEquals(X, result.expression());
        assertDetached(input, X, rules, result);
        assertEquals(261, result.diagnosticResult().orElseThrow().provenance().droppedWitnesses());
    }

    @Test
    void sharedInputExpansionFailsBeforeRecursiveInsertion() {
        Expr input = X;
        for (int i = 0; i < 80; i++) input = new BinaryExpr(input, BinaryOperator.ADD, input);
        var result = run(input, List.of());
        assertSame(input, result.expression());
        assertEquals(ProofOutcome.Status.LIMIT_EXCEEDED, result.proof().status());
        assertTrue(result.diagnosticResult().isEmpty());
    }

    @Test
    void sharedRuleExpansionFailsBeforeNativePatternTraversal() {
        PatternExpr source = A;
        for (int i = 0; i < 80; i++) source = new PatternExpr.Operation(BinaryOperator.ADD, source, source);
        var result = run(X, List.of(rule("shared", source, A)));
        assertSame(X, result.expression());
        assertEquals(ProofOutcome.Status.LIMIT_EXCEEDED, result.proof().status());
        assertTrue(result.diagnosticResult().isEmpty());
    }

    @Test
    void unavailableCostsReturnInputButRewritingCanMakeRootFinite() {
        Expr input = fn("f", X);
        var saturation = new EqualitySaturation(List.of(rule("erase", pattern("f", A), A)));
        var proved = saturation.saturateWithProof(input,
            node -> node.symbol().equals("fn:f") ? Integer.MAX_VALUE : 1,
            EqualitySaturation.ProofOptions.defaults());
        assertEquals(X, proved.expression());
        assertTrue(proved.proof().complete());
        var unavailable = saturation.saturateWithProof(input, node -> Integer.MAX_VALUE,
            EqualitySaturation.ProofOptions.defaults());
        assertSame(input, unavailable.expression());
        assertEquals(ProofOutcome.Status.EXTRACTION_UNAVAILABLE, unavailable.proof().status());
    }

    @Test
    void zeroCostLiveCycleKeepsFiniteTiedChoice() {
        var proved = new EqualitySaturation(List.of(rule("cycle", A, pattern("f", A))))
            .saturateWithProof(X, node -> 0, EqualitySaturation.ProofOptions.defaults());
        assertEquals(X, proved.expression());
        assertTrue(proved.proof().complete());
        assertEquals(1, proved.diagnosticResult().orElseThrow().stats().totalApplications());
    }

    @Test
    void unsupportedSubclassCannotExecuteInProofModeButLegacyRemainsAvailable() {
        AtomicInteger calls = new AtomicInteger();
        var custom = new PatternRewriteRule("custom", pattern("f", A), A) {
            @Override public boolean matches(Expr expression) {
                calls.incrementAndGet();
                return super.matches(expression);
            }
        };
        Expr input = fn("f", X);
        var saturation = new EqualitySaturation(List.of(custom));
        var proved = saturation.saturateWithProof(input, node -> 1, EqualitySaturation.ProofOptions.defaults());
        assertSame(input, proved.expression());
        assertEquals(ProofOutcome.Status.UNSUPPORTED_RULE, proved.proof().status());
        assertEquals(0, calls.get());
        var graph = new EGraph();
        var legacy = saturation.saturate(graph, graph.addExpression(input), node -> 1);
        assertEquals(X, legacy.expression());
        assertTrue(calls.get() > 0);
        assertFalse(legacy.provenance().hasExtractionProof());
    }

    @Test
    void initialGraphInsertionHonorsNodeCap() {
        Expr input = fn("outer", fn("inner", X));
        var proved = new EqualitySaturation(List.of(), new EqualitySaturation.Config(1, 2))
            .saturateWithProof(input, node -> 1, EqualitySaturation.ProofOptions.defaults());
        assertSame(input, proved.expression());
        assertEquals(ProofOutcome.Status.EXTRACTION_UNAVAILABLE, proved.proof().status());
        assertTrue(proved.diagnosticResult().isEmpty());
    }

    @Test
    void reconstructionExhaustionNeverPublishesImprovedOutput() {
        Expr input = fn("f", X);
        List<RewriteRule> rules = List.of(rule("erase", pattern("f", A), A));
        var baseline = run(input, rules);
        var original = ProofLimits.defaults();
        // Capture and bounded extraction fit exactly; no reconstruction work remains.
        var limited = withWork(original, baseline.proof().captureWork());
        var result = new EqualitySaturation(rules, new EqualitySaturation.Config(300, 10_000))
            .saturateWithProof(input, node -> 1,
                new EqualitySaturation.ProofOptions(limited, original));
        assertSame(input, result.expression());
        assertEquals(ProofOutcome.Status.LIMIT_EXCEEDED, result.proof().status());
        assertTrue(result.proof().detail().startsWith("build:"));
        assertEquals(X, result.diagnosticResult().orElseThrow().expression());
        assertFalse(result.diagnosticResult().orElseThrow().provenance().hasExtractionProof());
    }

    @Test
    void quotaLossAfterUnionDoesNotInterruptRepairOrRecoverCompleteness() {
        var limits = ProofLimits.defaults();
        var builder = new ExtractionProofBuilder(List.of(rule("a-b", pattern("a"), pattern("b"))), limits);
        var capture = new EGraphProofCapture(builder, limits);
        var graph = new EGraph(capture);
        var left = graph.addExpression(fn("a"));
        var right = graph.addExpression(fn("b"));
        var parentLeft = graph.addExpression(fn("parent", fn("a")));
        var parentRight = graph.addExpression(fn("parent", fn("b")));
        var instance = capture.prepare(graph, "a-b", Map.of());
        graph.union(left, right);
        builder.chargeWork(limits.maxWork() - builder.workUsed());
        assertDoesNotThrow(() -> capture.recorded(instance));
        assertFalse(capture.active());
        assertDoesNotThrow(graph::rebuild);
        assertTrue(graph.areEquivalent(parentLeft, parentRight));
        assertEquals(graph.find(parentLeft), graph.addExpression(fn("parent", fn("a"))));
        assertDoesNotThrow(() -> graph.addExpression(fn("new")));
        assertFalse(capture.active());
        assertEquals(ProofOutcome.Status.LIMIT_EXCEEDED, capture.unavailable().status());
    }

    @Test
    void scopedSymbolIdentitySurvivesCertificateAndExtraction() {
        Expr scoped = VariableExpr.scoped(new SymbolId(new UUID(0, 17), 1));
        Expr input = fn("f", scoped);
        List<RewriteRule> rules = List.of(rule("erase", pattern("f", A), A));
        var result = run(input, rules);
        assertEquals(scoped, result.expression());
        assertDetached(input, scoped, rules, result);
        Expr otherScope = VariableExpr.scoped(new SymbolId(new UUID(0, 18), 1));
        assertEquals(ExtractionProofChecker.Status.REJECTED, ExtractionProofChecker.check(
            input, otherScope, result.proof().checked().orElseThrow().certificate(), rules,
            ProofLimits.defaults()).status());
    }

    @Test
    void validLargeRationalCanBeCertifiedWithoutParserLimitedDiagnosticText() {
        Expr input = new NumberExpr(new ExactRational(BigInteger.ONE.shiftLeft(3500), BigInteger.ONE));
        var result = run(input, List.of());
        assertEquals(input, result.expression());
        assertTrue(result.proof().complete(), result.proof().detail());
        assertTrue(result.diagnosticResult().isEmpty());
    }

    @Test
    void repeatedLongLabelsCannotBypassFormattingCharacterLimit() {
        Expr repeated = new VariableExpr("x".repeat(200));
        Expr input = fn("many", java.util.Collections.nCopies(20, repeated).toArray(Expr[]::new));
        var limits = new ProofLimits(50_000, 100_000, 500_000, 500, 4096, 256, 100_000, 5_000_000);
        var result = new EqualitySaturation(List.of()).saturateWithProof(input, node -> 1,
            new EqualitySaturation.ProofOptions(limits, ProofLimits.defaults()));
        assertSame(input, result.expression());
        assertEquals(ProofOutcome.Status.LIMIT_EXCEEDED, result.proof().status());
        assertTrue(result.proof().detail().contains("rendering"));
        assertTrue(result.diagnosticResult().isEmpty());
    }

    @Test
    void customDepthCannotBypassNativeRecursionSafetyCeiling() {
        PatternExpr source = A;
        for (int i = 0; i < 600; i++) source = pattern("deep", source);
        var limits = new ProofLimits(50_000, 100_000, 500_000, 1_000_000, 4096, 2048, 100_000, 5_000_000);
        var result = new EqualitySaturation(List.of(rule("deep", source, A))).saturateWithProof(X, node -> 1,
            new EqualitySaturation.ProofOptions(limits, ProofLimits.defaults()));
        assertSame(X, result.expression());
        assertEquals(ProofOutcome.Status.LIMIT_EXCEEDED, result.proof().status());
        assertTrue(result.diagnosticResult().isEmpty());
    }

    @Test
    void ownNodeRepairAndParentRepairAreExplainedByCapturedGroundRules() {
        Expr input = fn("pair", fn("p", fn("a")), fn("q", fn("b")));
        List<RewriteRule> rules = List.of(
            rule("p-q", pattern("p", A), pattern("q", A)),
            rule("a-b", pattern("a"), pattern("b")),
            rule("same-pair", pattern("pair", A, A), A));
        var result = new EqualitySaturation(rules).saturateWithProof(input,
            node -> node.symbol().equals("fn:q") || node.symbol().equals("fn:b") ? 0 : 1,
            EqualitySaturation.ProofOptions.defaults());
        assertEquals(fn("q", fn("b")), result.expression());
        assertDetached(input, fn("q", fn("b")), rules, result);
        assertTrue(result.diagnosticResult().orElseThrow().stats().appliedRules().containsKey("same-pair"));
    }

    @Test
    void nativeRhsReusesExistingHashConsedNodeAfterAChildUnion() {
        Expr input = fn("outer", fn("g", fn("a"), fn("b")), fn("f", fn("a")));
        List<RewriteRule> rules = List.of(rule("a-b", pattern("a"), pattern("b")),
            rule("same-g", pattern("g", A, A), pattern("f", A)));
        var result = run(input, rules);
        Expr expected = fn("outer", fn("f", fn("a")), fn("f", fn("a")));
        assertEquals(expected, result.expression());
        assertDetached(input, expected, rules, result);
    }

    @Test
    void constructorPreflightReportsConsumedWorkWhenQuotaIsExhausted() {
        Expr input = fn("f", X);
        for (long budget : new long[] {0, 1, 2}) {
            var result = new EqualitySaturation(List.of(rule("r", pattern("f", A), A)),
                    new EqualitySaturation.Config(10, 100)).saturateWithProof(input, node -> 1,
                    new EqualitySaturation.ProofOptions(withWork(ProofLimits.defaults(), budget), ProofLimits.defaults()));
            assertEquals(ProofOutcome.Status.LIMIT_EXCEEDED, result.proof().status());
            assertSame(input, result.expression());
            assertEquals(budget, result.proof().captureWork());
            assertEquals(0, result.proof().buildWork());
            assertEquals(0, result.proof().replayWork());
        }
    }

    @Test
    void constructorPreflightCountsSpentWorkInsteadOfRejectedCharge() {
        var result = new EqualitySaturation(List.of(rule("long-rule-id", A, A)),
                new EqualitySaturation.Config(10, 100)).saturateWithProof(X, node -> 1,
                new EqualitySaturation.ProofOptions(withWork(ProofLimits.defaults(), 5), ProofLimits.defaults()));
        assertEquals(ProofOutcome.Status.LIMIT_EXCEEDED, result.proof().status());
        assertEquals(1, result.proof().captureWork());
    }

    @Test
    void unsupportedConstructorPreflightRetainsConsumedWork() {
        var unsupported = new PatternRewriteRule("r", A, A) {};
        var result = run(X, List.of(unsupported));
        assertEquals(ProofOutcome.Status.UNSUPPORTED_RULE, result.proof().status());
        assertEquals(1, result.proof().captureWork());
    }

    @Test
    void malformedConstructorPreflightRetainsConsumedWork() {
        var scalar = new OpaquePreflightInteger();
        var rational = new ExactRational(scalar, BigInteger.ONE);
        scalar.armed = true;
        var result = run(X, List.of(rule("r", PatternExpr.num(rational), A)));
        assertEquals(ProofOutcome.Status.REPLAY_REJECTED, result.proof().status());
        assertEquals(3, result.proof().captureWork());
        assertSame(X, result.expression());
    }

    private static final class OpaquePreflightInteger extends BigInteger {
        private boolean armed;
        OpaquePreflightInteger() { super("7"); }
        @Override public BigInteger divide(BigInteger value) { return this; }
        @Override public BigInteger gcd(BigInteger value) { return BigInteger.ONE; }
        @Override public int bitLength() {
            if (armed) throw new AssertionError("opaque scalar inspected");
            return super.bitLength();
        }
    }

    private static ProofLimits withWork(ProofLimits limits, long work) {
        return new ProofLimits(limits.maxTerms(), limits.maxProofSteps(), limits.maxReferences(),
            limits.maxCharacters(), limits.maxRationalBits(), limits.maxDepth(), limits.maxExpandedNodes(), work);
    }

    private static EqualitySaturation.ProvedResult run(Expr input, List<RewriteRule> rules) {
        return new EqualitySaturation(rules, new EqualitySaturation.Config(300, 10_000))
            .saturateWithProof(input, node -> 1, EqualitySaturation.ProofOptions.defaults());
    }

    private static void assertDetached(Expr input, Expr output, List<RewriteRule> rules,
            EqualitySaturation.ProvedResult result) {
        assertEquals(ProofOutcome.Status.COMPLETE, result.proof().status(), result.proof().detail());
        var token = result.proof().checked().orElseThrow();
        assertSame(input, token.input());
        assertSame(result.expression(), token.output());
        assertEquals(ExtractionProofChecker.Status.ACCEPTED,
            ExtractionProofChecker.check(input, output, token.certificate(), rules, ProofLimits.defaults()).status());
    }

    private static FunctionExpr fn(String name, Expr... arguments) {
        return new FunctionExpr(name, List.of(arguments));
    }

    private static PatternExpr.Function pattern(String name, PatternExpr... arguments) {
        return new PatternExpr.Function(name, List.of(arguments));
    }

    private static PatternRewriteRule rule(String id, PatternExpr before, PatternExpr after) {
        return new PatternRewriteRule(id, before, after);
    }
}
