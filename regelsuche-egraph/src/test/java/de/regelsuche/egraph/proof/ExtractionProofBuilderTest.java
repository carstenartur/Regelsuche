package de.regelsuche.egraph.proof;

import static de.regelsuche.egraph.proof.ExtractionProofCheckerTest.*;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.transform.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class ExtractionProofBuilderTest {
    @Test
    void groundClosureProvesRepeatedBindingBridge() {
        var repeated =
                new PatternRewriteRule(
                        "repeat",
                        PatternExpr.fn("g", PatternExpr.var("A"), PatternExpr.var("A")),
                        PatternExpr.fn("h", PatternExpr.var("A")));
        var rules = List.of(rule(), repeated);
        var b = new ExtractionProofBuilder(rules, ProofLimits.defaults());
        var input = fn("g", fn("a"), fn("b"));
        var output = fn("h", fn("a"));
        int in = b.addExpression(input),
                out = b.addExpression(output),
                a = b.addExpression(fn("a"));
        b.recordInstance(b.prepareInstance("ab", Map.of()));
        b.recordInstance(b.prepareInstance("repeat", Map.of("A", a)));
        var result = b.build(in, out);
        assertEquals(ExtractionProofBuilder.BuildStatus.BUILT, result.status());
        var checked =
                ExtractionProofChecker.check(
                        input,
                        output,
                        result.certificate().orElseThrow(),
                        rules,
                        ProofLimits.defaults());
        assertEquals(ExtractionProofChecker.Status.ACCEPTED, checked.status(), checked.detail());
    }

    @Test
    void sharedDagExpansionIsRejectedBeforeMaterialization() {
        Expr e = new VariableExpr("x");
        for (int i = 0; i < 30; i++) e = new FunctionExpr("g", List.of(e, e));
        var b = new ExtractionProofBuilder(List.of(), ProofLimits.defaults());
        Expr input = e;
        assertThrows(ExtractionProofBuilder.LimitExceeded.class, () -> b.addExpression(input));
        assertThrows(ExtractionProofBuilder.LimitExceeded.class, () -> b.addExpression(fn("a")));
    }

    @Test
    void rulePreflightPrecedesRecursivePatternHelpers() {
        PatternExpr p = PatternExpr.var("A");
        for (int i = 0; i < 30; i++) p = PatternExpr.fn("g", p, p);
        var r = new PatternRewriteRule("huge", p, PatternExpr.var("A"));
        assertThrows(
                ExtractionProofBuilder.LimitExceeded.class,
                () -> new ExtractionProofBuilder(List.of(r), ProofLimits.defaults()));
    }

    @Test
    void unsupportedRulesAndDuplicateIdsReject() {
        var bad =
                List.of(
                        new PatternRewriteRule("v", PatternExpr.variable("x"), PatternExpr.num(1)),
                        new PatternRewriteRule("u", PatternExpr.var("A"), PatternExpr.var("B")),
                        new PatternRewriteRule(
                                "ac",
                                PatternExpr.var("A"),
                                PatternExpr.var("A"),
                                RecognitionProfile.arithmeticAc()));
        for (var r : bad)
            assertThrows(
                    ExtractionProofBuilder.UnsupportedRule.class,
                    () -> new ExtractionProofBuilder(List.of(r), ProofLimits.defaults()));
        assertThrows(
                ExtractionProofBuilder.UnsupportedRule.class,
                () -> new ExtractionProofBuilder(List.of(rule(), rule()), ProofLimits.defaults()));
        var subclass =
                new PatternRewriteRule("sub", PatternExpr.var("A"), PatternExpr.var("A")) {
                    @Override
                    public String id() {
                        throw new AssertionError("callback");
                    }
                };
        assertThrows(
                ExtractionProofBuilder.UnsupportedRule.class,
                () -> new ExtractionProofBuilder(List.of(subclass), ProofLimits.defaults()));
    }

    @Test
    void oversizedCharacterReferenceAndRationalInputsReject() {
        var small = new ProofLimits(100, 100, 2, 20, 8, 10, 100, 10000);
        assertThrows(
                ExtractionProofBuilder.LimitExceeded.class,
                () ->
                        new ExtractionProofBuilder(List.of(), small)
                                .addExpression(fn("long".repeat(20))));
        assertThrows(
                ExtractionProofBuilder.LimitExceeded.class,
                () ->
                        new ExtractionProofBuilder(List.of(), small)
                                .addExpression(fn("f", fn("a"), fn("a"), fn("a"))));
        assertThrows(
                ExtractionProofBuilder.LimitExceeded.class,
                () ->
                        new ExtractionProofBuilder(List.of(), small)
                                .addExpression(new NumberExpr(1000)));
    }

    @Test
    void moreThan256PremisesRemainAuthoritative() {
        var rules = new ArrayList<PatternRewriteRule>();
        for (int i = 0; i < 270; i++)
            rules.add(
                    new PatternRewriteRule(
                            "r" + i, PatternExpr.fn("f" + i), PatternExpr.fn("f" + (i + 1))));
        var b = new ExtractionProofBuilder(rules, ProofLimits.defaults());
        var in = fn("f0");
        var out = fn("f270");
        int a = b.addExpression(in), z = b.addExpression(out);
        for (var r : rules) b.recordInstance(b.prepareInstance(r.id(), Map.of()));
        var result = b.build(a, z);
        assertEquals(ExtractionProofBuilder.BuildStatus.BUILT, result.status(), result.detail());
        var checked =
                ExtractionProofChecker.check(
                        in, out, result.certificate().orElseThrow(), rules, ProofLimits.defaults());
        assertEquals(ExtractionProofChecker.Status.ACCEPTED, checked.status(), checked.detail());
    }

    @Test
    void unrecordedPreparedInstanceIsNotAnAxiom() {
        var b = new ExtractionProofBuilder(List.of(rule()), ProofLimits.defaults());
        var instance = b.prepareInstance("ab", Map.of());
        assertEquals(
                ExtractionProofBuilder.BuildStatus.NO_DERIVATION,
                b.build(instance.before(), instance.after()).status());
    }

    @Test
    void forgedGroundInstanceRejects() {
        var b = new ExtractionProofBuilder(List.of(rule()), ProofLimits.defaults());
        int a = b.addExpression(fn("a"));
        int z = b.addExpression(fn("z"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        b.recordInstance(
                                new ExtractionProofBuilder.GroundInstance(0, Map.of(), a, z)));
    }

    @Test
    void producerExactAndOneShortWorkBudgets() {
        long used = reflexiveBuild(ProofLimits.defaults()).workUsed();
        assertEquals(
                ExtractionProofBuilder.BuildStatus.BUILT, reflexiveBuild(withWork(used)).status());
        assertEquals(
                ExtractionProofBuilder.BuildStatus.LIMIT_EXCEEDED,
                reflexiveBuild(withWork(used - 1)).status());
    }

    static ExtractionProofBuilder.BuildResult reflexiveBuild(ProofLimits limits) {
        var b = new ExtractionProofBuilder(List.of(), limits);
        int a = b.addExpression(fn("a"));
        return b.build(a, a);
    }

    @Test
    void opaqueScalarIsRejectedBeforeCallbacks() {
        var scalar = new OpaqueInteger();
        var rational = new de.regelsuche.scalar.ExactRational(scalar, java.math.BigInteger.ONE);
        scalar.armed = true;
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ExtractionProofBuilder(List.of(), ProofLimits.defaults())
                                .addExpression(new NumberExpr(rational)));
        var rule = new PatternRewriteRule("opaque", PatternExpr.num(rational), PatternExpr.num(1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtractionProofBuilder(List.of(rule), ProofLimits.defaults()));
    }

    static final class OpaqueInteger extends java.math.BigInteger {
        boolean armed;

        OpaqueInteger() {
            super("7");
        }

        @Override
        public java.math.BigInteger divide(java.math.BigInteger d) {
            return this;
        }

        @Override
        public java.math.BigInteger gcd(java.math.BigInteger d) {
            return java.math.BigInteger.ONE;
        }

        @Override
        public int bitLength() {
            if (armed) throw new AssertionError("opaque bitLength callback");
            return super.bitLength();
        }

        @Override
        public int hashCode() {
            if (armed) throw new AssertionError("opaque hash callback");
            return super.hashCode();
        }

        @Override
        public String toString() {
            if (armed) throw new AssertionError("opaque text callback");
            return super.toString();
        }
    }

    @Test
    void maximumBitCanonicalNumberMaterializes() {
        var n =
                new NumberExpr(
                        de.regelsuche.scalar.ExactRational.integer(
                                java.math.BigInteger.ONE.shiftLeft(4095)));
        var b = new ExtractionProofBuilder(List.of(), ProofLimits.defaults());
        int id = b.addExpression(n);
        assertEquals(n, b.materialize(id));
    }

    @Test
    void terminalLimitCannotBecomeNoDerivation() {
        var b = new ExtractionProofBuilder(List.of(), withWork(1000));
        int a = b.addExpression(fn("a"));
        int z = b.addExpression(fn("z"));
        assertThrows(ExtractionProofBuilder.LimitExceeded.class, () -> b.chargeWork(1001));
        assertEquals(ExtractionProofBuilder.BuildStatus.LIMIT_EXCEEDED, b.build(a, z).status());
    }

    @Test
    void malformedDirectArgumentsUseIllegalArgumentException() {
        var b = new ExtractionProofBuilder(List.of(rule()), ProofLimits.defaults());
        assertThrows(IllegalArgumentException.class, () -> b.addExpression(null));
        assertThrows(IllegalArgumentException.class, () -> b.addTerm(null));
        assertThrows(IllegalArgumentException.class, () -> b.prepareInstance("ab", null));
        assertThrows(IllegalArgumentException.class, () -> b.recordInstance(null));
    }

    @Test
    void directlyRecordedGroundInstancesChargeRetainedBindingCharacters() {
        var rule = new PatternRewriteRule("r", PatternExpr.var("A"), PatternExpr.var("A"));
        var d = ProofLimits.defaults();
        var limits =
                new ProofLimits(
                        d.maxTerms(),
                        d.maxProofSteps(),
                        d.maxReferences(),
                        5,
                        d.maxRationalBits(),
                        d.maxDepth(),
                        d.maxExpandedNodes(),
                        d.maxWork());
        var b = new ExtractionProofBuilder(List.of(rule), limits);
        int a = b.addExpression(fn("a"));
        b.recordInstance(
                new ExtractionProofBuilder.GroundInstance(0, Map.of(new String("A"), a), a, a));
        assertThrows(
                ExtractionProofBuilder.LimitExceeded.class,
                () ->
                        b.recordInstance(
                                new ExtractionProofBuilder.GroundInstance(
                                        0, Map.of(new String("A"), a), a, a)));
        assertEquals(ExtractionProofBuilder.BuildStatus.LIMIT_EXCEEDED, b.build(a, a).status());
    }

    @Test
    void nullBindingKeysAndValuesUseIllegalArgumentException() {
        var rule = new PatternRewriteRule("r", PatternExpr.var("A"), PatternExpr.var("A"));
        var b = new ExtractionProofBuilder(List.of(rule), ProofLimits.defaults());
        int a = b.addExpression(fn("a"));
        var nullKey = new HashMap<String, Integer>();
        nullKey.put(null, a);
        var nullValue = new HashMap<String, Integer>();
        nullValue.put("A", null);
        assertAll(
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> b.prepareInstance("r", nullKey)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> b.prepareInstance("r", nullValue)));
    }
}
