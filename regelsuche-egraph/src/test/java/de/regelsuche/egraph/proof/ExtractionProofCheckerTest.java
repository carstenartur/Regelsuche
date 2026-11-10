package de.regelsuche.egraph.proof;

import static de.regelsuche.egraph.proof.ExtractionCertificate.*;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.transform.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class ExtractionProofCheckerTest {
    static PatternRewriteRule rule() {
        return new PatternRewriteRule("ab", PatternExpr.fn("a"), PatternExpr.fn("b"));
    }

    static Expr fn(String n, Expr... a) {
        return new FunctionExpr(n, List.of(a));
    }

    @Test
    void detachedCheckerAcceptsEveryInferenceKind() {
        var c =
                new ExtractionCertificate(
                        1,
                        List.of(
                                new Term(Kind.FUNCTION, "a", List.of()),
                                new Term(Kind.FUNCTION, "b", List.of()),
                                new Term(Kind.FUNCTION, "f", List.of(0)),
                                new Term(Kind.FUNCTION, "f", List.of(1))),
                        List.of(new RuleIdentity("ab", rule().source(), rule().target())),
                        List.of(
                                new PatternInstance(0, Map.of(), 0, 1),
                                new Reflexivity(0),
                                new Symmetry(0),
                                new Transitivity(1, 0),
                                new Congruence(2, 3, List.of(3))),
                        2,
                        3,
                        4);
        assertEquals(
                ExtractionProofChecker.Status.ACCEPTED,
                ExtractionProofChecker.check(
                                fn("f", fn("a")),
                                fn("f", fn("b")),
                                c,
                                List.of(rule()),
                                ProofLimits.defaults())
                        .status());
    }

    static ExtractionCertificate leafCertificate(String label) {
        return new ExtractionCertificate(
                1,
                List.of(new Term(Kind.FUNCTION, label, List.of())),
                List.of(),
                List.of(new Reflexivity(0)),
                0,
                0,
                0);
    }

    @Test
    void sameLabelDifferentScopeRejects() {
        var a = VariableExpr.scoped(new de.regelsuche.symbol.SymbolId(new UUID(0, 1), 1));
        var b = VariableExpr.scoped(new de.regelsuche.symbol.SymbolId(new UUID(0, 2), 1));
        var c =
                new ExtractionCertificate(
                        1,
                        List.of(new Term(Kind.VARIABLE, a.name(), List.of())),
                        List.of(),
                        List.of(new Reflexivity(0)),
                        0,
                        0,
                        0);
        assertEquals(
                ExtractionProofChecker.Status.REJECTED,
                ExtractionProofChecker.check(a, b, c, List.of(), ProofLimits.defaults()).status());
    }

    @Test
    void malformedProofNodesReject() {
        var terms =
                List.of(
                        new Term(Kind.FUNCTION, "a", List.of()),
                        new Term(Kind.FUNCTION, "b", List.of()),
                        new Term(Kind.FUNCTION, "f", List.of(0, 1)),
                        new Term(Kind.FUNCTION, "f", List.of(1, 0)));
        List<List<Step>> wrong =
                List.of(
                        List.of(new Symmetry(0)),
                        List.of(new Reflexivity(0), new Reflexivity(1), new Transitivity(0, 1)),
                        List.of(
                                new Reflexivity(0),
                                new Reflexivity(1),
                                new Congruence(2, 3, List.of(0, 1))));
        for (var steps : wrong) {
            var c = new ExtractionCertificate(1, terms, List.of(), steps, 0, 1, steps.size() - 1);
            assertEquals(
                    ExtractionProofChecker.Status.REJECTED,
                    ExtractionProofChecker.check(
                                    fn("a"), fn("b"), c, List.of(), ProofLimits.defaults())
                            .status());
        }
    }

    @Test
    void substitutedRuleAndBindingsReject() {
        var rule =
                new PatternRewriteRule(
                        "r", PatternExpr.fn("f", PatternExpr.var("A")), PatternExpr.var("A"));
        var terms =
                List.of(
                        new Term(Kind.VARIABLE, "x", List.of()),
                        new Term(Kind.FUNCTION, "f", List.of(0)));
        for (var bindings :
                List.<Map<String, Integer>>of(Map.of(), Map.of("A", 0, "B", 0), Map.of("B", 0))) {
            var c =
                    new ExtractionCertificate(
                            1,
                            terms,
                            List.of(new RuleIdentity("r", rule.source(), rule.target())),
                            List.of(new PatternInstance(0, bindings, 1, 0)),
                            1,
                            0,
                            0);
            assertEquals(
                    ExtractionProofChecker.Status.REJECTED,
                    ExtractionProofChecker.check(
                                    fn("f", new VariableExpr("x")),
                                    new VariableExpr("x"),
                                    c,
                                    List.of(rule),
                                    ProofLimits.defaults())
                            .status());
        }
        var c =
                new ExtractionCertificate(
                        1,
                        terms,
                        List.of(new RuleIdentity("r", rule.source(), PatternExpr.fn("q"))),
                        List.of(new PatternInstance(0, Map.of("A", 0), 1, 0)),
                        1,
                        0,
                        0);
        assertEquals(
                ExtractionProofChecker.Status.REJECTED,
                ExtractionProofChecker.check(
                                fn("f", new VariableExpr("x")),
                                new VariableExpr("x"),
                                c,
                                List.of(rule),
                                ProofLimits.defaults())
                        .status());
    }

    @Test
    void duplicateAndForwardTermsReject() {
        for (var terms :
                List.of(
                        List.of(
                                new Term(Kind.FUNCTION, "a", List.of()),
                                new Term(Kind.FUNCTION, "a", List.of())),
                        List.of(new Term(Kind.FUNCTION, "a", List.of(0))))) {
            var c =
                    new ExtractionCertificate(
                            1, terms, List.of(), List.of(new Reflexivity(0)), 0, 0, 0);
            assertEquals(
                    ExtractionProofChecker.Status.REJECTED,
                    ExtractionProofChecker.check(
                                    fn("a"), fn("a"), c, List.of(), ProofLimits.defaults())
                            .status());
        }
    }

    @Test
    void exactWorkBudgetAccepts() {
        var c = leafCertificate("a");
        var first =
                ExtractionProofChecker.check(
                        fn("a"), fn("a"), c, List.of(), ProofLimits.defaults());
        var exact =
                ExtractionProofChecker.check(
                        fn("a"), fn("a"), c, List.of(), withWork(first.workUsed()));
        assertEquals(ExtractionProofChecker.Status.ACCEPTED, exact.status());
    }

    @Test
    void oneShortWorkBudgetIsLimitExceeded() {
        var c = leafCertificate("a");
        var first =
                ExtractionProofChecker.check(
                        fn("a"), fn("a"), c, List.of(), ProofLimits.defaults());
        assertEquals(
                ExtractionProofChecker.Status.LIMIT_EXCEEDED,
                ExtractionProofChecker.check(
                                fn("a"), fn("a"), c, List.of(), withWork(first.workUsed() - 1))
                        .status());
    }

    static ProofLimits withWork(long work) {
        var d = ProofLimits.defaults();
        return new ProofLimits(
                d.maxTerms(),
                d.maxProofSteps(),
                d.maxReferences(),
                d.maxCharacters(),
                d.maxRationalBits(),
                d.maxDepth(),
                d.maxExpandedNodes(),
                work);
    }

    @Test
    void completeOutcomeRequiresCheckerToken() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ProofOutcome.unavailable(ProofOutcome.Status.COMPLETE, "fake", 0, 0, 0));
        assertThrows(NullPointerException.class, () -> ProofOutcome.complete(null, 0, 0));
        var e = fn("a");
        var checked =
                ExtractionProofChecker.check(
                                e, e, leafCertificate("a"), List.of(), ProofLimits.defaults())
                        .checked()
                        .orElseThrow();
        var complete = ProofOutcome.complete(checked, 3, 4);
        assertTrue(complete.complete());
        assertSame(e, complete.checked().orElseThrow().output());
        assertTrue(
                Arrays.stream(
                                ExtractionProofChecker.CheckedCertificate.class
                                        .getDeclaredConstructors())
                        .allMatch(c -> java.lang.reflect.Modifier.isPrivate(c.getModifiers())));
    }

    @Test
    void constructorCollectionsAreFrozen() {
        var children = new ArrayList<Integer>();
        var term = new Term(Kind.FUNCTION, "a", children);
        children.add(0);
        assertTrue(term.children().isEmpty());
        var map = new HashMap<String, Integer>();
        map.put("A", 0);
        var instance = new PatternInstance(0, map, 0, 0);
        map.put("A", 1);
        assertEquals(0, instance.bindings().get("A"));
        var terms = new ArrayList<Term>();
        terms.add(term);
        var steps = new ArrayList<Step>();
        steps.add(new Reflexivity(0));
        var c = new ExtractionCertificate(1, terms, List.of(), steps, 0, 0, 0);
        terms.clear();
        steps.clear();
        assertEquals(
                ExtractionProofChecker.Status.ACCEPTED,
                ExtractionProofChecker.check(fn("a"), fn("a"), c, List.of(), ProofLimits.defaults())
                        .status());
    }

    @Test
    void canonicalRationalBeyondLiteralDigitsAccepts() {
        var value = de.regelsuche.scalar.ExactRational.integer(java.math.BigInteger.TEN.pow(600));
        var e = new NumberExpr(value);
        var c =
                new ExtractionCertificate(
                        1,
                        List.of(new Term(Kind.NUMBER, value.canonicalText(), List.of())),
                        List.of(),
                        List.of(new Reflexivity(0)),
                        0,
                        0,
                        0);
        assertEquals(
                ExtractionProofChecker.Status.ACCEPTED,
                ExtractionProofChecker.check(e, e, c, List.of(), ProofLimits.defaults()).status());
    }
}
