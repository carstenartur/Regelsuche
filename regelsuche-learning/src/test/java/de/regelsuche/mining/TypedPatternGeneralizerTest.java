package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.transform.PatternRewriteRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TypedPatternGeneralizerTest {
    private final TypedPatternGeneralizer generalizer = new TypedPatternGeneralizer();

    @Test void legacyRenamedModularExamplesProduceNoMultiExampleAbstraction() {
        var scorer = new ExpressionScorer();
        var paths = new ArrayList<SuccessfulTransformationPath>();
        for (var names : List.of(List.of("a", "q", "e", "n"), List.of("b", "u", "v", "m"))) {
            String a = names.get(0), q = names.get(1), e = names.get(2), n = names.get(3);
            String source = "program(modpow(" + a + "," + q + "*" + e + "," + n
                + "),modpow(" + a + "," + e + "," + n + "))";
            String target = "program(modpow(modpow(" + a + "," + e + "," + n + "),"
                + q + "," + n + "),modpow(" + a + "," + e + "," + n + "))";
            paths.add(new SuccessfulTransformationPath("syntax-fixture-" + a, source, target,
                List.of(source, target), List.of("syntax-fixture"), scorer.score(source),
                scorer.score(target), false, "NOT_A_PROOF", Map.of(), List.of()));
        }
        assertTrue(new PatternGeneralizer().generalize(paths).isEmpty(),
            "characterize legacy normalization; do not change its historical behavior here");
    }

    @Test void reconstructsBothSidesOfRenamedModularPowerExamples() {
        var examples = List.of(modular("a", "q", "e", "n"), modular("b", "u", "v", "m"));
        var candidate = generalizer.generalize(examples).orElseThrow();
        assertReconstructs(candidate);
        assertEquals(4, candidate.bindings().getFirst().size());
        assertEquals(examples, candidate.examples());
    }

    @Test void sourceTargetReorderingSharesTheSamePlaceholderBindings() {
        var candidate = generalizer.generalize(List.of(pair("x", "y"), pair("u", "v"))).orElseThrow();
        assertReconstructs(candidate);
        var rule = syntaxOnly(candidate);
        Expr probe = f("f", v("p"), v("q"), v("p"));
        assertTrue(rule.matches(probe));
        assertEquals(f("g", v("q"), v("p")), rule.apply(probe));
    }

    @Test void repeatedPlaceholderRejectsDifferentScopedSymbols() {
        Expr a = VariableExpr.scoped(new SymbolId(new UUID(0, 7), 1));
        Expr b = VariableExpr.scoped(new SymbolId(new UUID(0, 7), 2));
        var candidate = generalizer.generalize(List.of(
            new TypedPatternGeneralizer.Example(f("repeat", a, a), a),
            new TypedPatternGeneralizer.Example(f("repeat", b, b), b))).orElseThrow();
        assertReconstructs(candidate);
        assertFalse(syntaxOnly(candidate).matches(f("repeat", a, b)));
        assertEquals(1, candidate.bindings().getFirst().size());
    }

    @Test void exactRationalLeavesAndBinaryGroupingSurviveWithoutNormalization() {
        Expr fraction = NumberExpr.exact("1/3");
        Expr large = NumberExpr.exact("123456789012345678901234567890");
        var examples = List.of(v("x"), v("y")).stream().map(variable -> {
            Expr grouped = new BinaryExpr(variable, BinaryOperator.ADD,
                new BinaryExpr(fraction, BinaryOperator.ADD, large));
            return new TypedPatternGeneralizer.Example(f("retain", grouped), grouped);
        }).toList();
        var candidate = generalizer.generalize(examples).orElseThrow();
        assertReconstructs(candidate);
        assertEquals(1, candidate.bindings().getFirst().size(), "exact constants are not renamed holes");
    }

    @Test void rejectsTargetOnlyPlaceholdersRatherThanInventingBindings() {
        assertTrue(generalizer.generalize(List.of(
            new TypedPatternGeneralizer.Example(f("f", v("x")), v("missing")),
            new TypedPatternGeneralizer.Example(f("f", v("y")), v("other")))).isEmpty());
    }

    @Test void identicalSubtreeVectorsAreSharedAcrossShapeDifferences() {
        Expr a = new BinaryExpr(v("x"), BinaryOperator.ADD, new NumberExpr(1));
        Expr b = f("h", v("y"));
        var candidate = generalizer.generalize(List.of(
            new TypedPatternGeneralizer.Example(f("repeat", a, a), a),
            new TypedPatternGeneralizer.Example(f("repeat", b, b), b))).orElseThrow();
        assertReconstructs(candidate);
        assertEquals(1, candidate.bindings().getFirst().size());
        assertFalse(syntaxOnly(candidate).matches(f("repeat", a, b)));
    }

    @Test void retainsAssumptionsAsImmutableSampleDataWithoutClaimingAGeneralProof() {
        var assumptions = new ArrayList<>(List.of("x integer"));
        var first = new TypedPatternGeneralizer.Example(f("f", v("x")), v("x"), assumptions);
        assumptions.clear();
        assertEquals(List.of("x integer"), first.assumptions());
        var candidate = generalizer.generalize(List.of(first,
            new TypedPatternGeneralizer.Example(f("f", v("y")), v("y"), List.of("y integer"))))
            .orElseThrow();
        assertThrows(UnsupportedOperationException.class, () -> candidate.examples().clear());
        assertThrows(UnsupportedOperationException.class, () -> candidate.bindings().getFirst().clear());
        assertEquals(List.of("y integer"), candidate.examples().get(1).assumptions());
    }

    @Test void patternStructureIsDeterministicUnderExampleReordering() {
        var left = pair("x", "y");
        var right = pair("u", "v");
        var a = generalizer.generalize(List.of(left, right)).orElseThrow();
        var b = generalizer.generalize(List.of(right, left)).orElseThrow();
        assertEquals(a.source(), b.source());
        assertEquals(a.target(), b.target());
    }

    @Test void rejectsExcessiveInputBeforeRecursiveGeneralization() {
        var pair = pair("x", "y");
        assertThrows(IllegalArgumentException.class, () -> generalizer.generalize(List.of(pair)));
        assertThrows(IllegalArgumentException.class, () -> generalizer.generalize(
            java.util.Collections.nCopies(TypedPatternGeneralizer.MAXIMUM_EXAMPLES + 1, pair)));
        Expr deep = v("x");
        for (int i = 0; i <= TypedPatternGeneralizer.MAXIMUM_DEPTH; i++) deep = f("f", deep);
        var oversized = new TypedPatternGeneralizer.Example(deep, v("x"));
        assertThrows(IllegalArgumentException.class, () -> generalizer.generalize(List.of(oversized, oversized)));
    }

    private static void assertReconstructs(TypedPatternGeneralizer.Candidate candidate) {
        for (int i = 0; i < candidate.examples().size(); i++) {
            var example = candidate.examples().get(i);
            assertEquals(example.source(), candidate.source().instantiate(candidate.bindings().get(i)));
            assertEquals(example.target(), candidate.target().instantiate(candidate.bindings().get(i)));
        }
    }

    // Tests structural matching only. These arbitrary f/g fixtures are NOT mathematical identities.
    private static PatternRewriteRule syntaxOnly(TypedPatternGeneralizer.Candidate candidate) {
        return new PatternRewriteRule("test-syntax-only", candidate.source(), candidate.target(),
            de.regelsuche.transform.RewriteKind.NORMALIZE, false, 0, false);
    }

    private static TypedPatternGeneralizer.Example pair(String x, String y) {
        return new TypedPatternGeneralizer.Example(f("f", v(x), v(y), v(x)), f("g", v(y), v(x)));
    }

    private static TypedPatternGeneralizer.Example modular(String a, String q, String e, String n) {
        Expr residue = f("modpow", v(a), v(e), v(n));
        Expr original = f("modpow", v(a), new BinaryExpr(v(q), BinaryOperator.MUL, v(e)), v(n));
        return new TypedPatternGeneralizer.Example(f("program", original, residue),
            f("program", f("modpow", residue, v(q), v(n)), residue));
    }

    private static VariableExpr v(String name) { return new VariableExpr(name); }
    private static FunctionExpr f(String name, Expr... arguments) {
        return new FunctionExpr(name, List.of(arguments));
    }
}
