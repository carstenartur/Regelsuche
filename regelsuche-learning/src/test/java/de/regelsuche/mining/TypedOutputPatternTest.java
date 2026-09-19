package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.transform.PatternExpr;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Development syntax fixtures, NOT equivalent mathematical f/g identities. */
class TypedOutputPatternTest {
    @Test void appliesAnActuallyGeneralizedHypothesisToRenamedOutputs() {
        var learned = learned();
        Expr z = v("renamed");
        FunctionExpr input = f("bundle", f("f", z), f("keep", z));
        var result = learned.find(input, 20);
        assertTrue(result.complete());
        assertEquals(2, result.assignmentAttempts());
        assertTrue(result.matcherSteps() > 0);
        var application = only(result);
        assertEquals(f("bundle", f("g", z), f("keep", z)), application.target());
        assertEquals(List.of(0, 1), application.positions());
        assertSame(input, application.source());
        assertEquals(List.of("x integer"), learned.hypothesis().examples().getFirst().assumptions());
    }

    @Test void transfersAcrossSwappedAndAdditionalOutputsWithoutMovingUnmatchedData() {
        Expr z = v("other");
        Expr extra = f("independent", NumberExpr.exact("1/3"));
        FunctionExpr input = f("bundle", f("keep", z), extra, f("f", z));
        var result = learned().find(input, 20);
        assertTrue(result.complete());
        var application = only(result);
        assertEquals(List.of(2, 0), application.positions());
        assertEquals(f("bundle", f("keep", z), extra, f("g", z)), application.target());
        assertSame(extra, application.target().arguments().get(1));
    }

    @Test void sharesBindingsAcrossAllMatchedOutputs() {
        var result = learned().find(f("bundle", f("f", v("x")), f("keep", v("y"))), 20);
        assertTrue(result.complete());
        assertTrue(result.applications().isEmpty());
        assertEquals(2, result.assignmentAttempts());
    }

    @Test void doesNotConfuseDistinctScopedSymbols() {
        Expr a = VariableExpr.scoped(new SymbolId(new UUID(0, 17), 1));
        Expr b = VariableExpr.scoped(new SymbolId(new UUID(0, 17), 2));
        assertTrue(learned().find(f("bundle", f("f", a), f("keep", b)), 20).applications().isEmpty());
        var application = only(learned().find(f("bundle", f("keep", a), f("f", a)), 20));
        assertEquals(f("bundle", f("keep", a), f("g", a)), application.target());
    }

    @Test void enumeratesDistinctPhysicalAssignmentsAndFreezesTheirData() {
        Expr x = v("x");
        var result = learned().find(f("bundle", f("f", x), f("keep", x), f("keep", x)), 20);
        assertTrue(result.complete());
        assertEquals(List.of(List.of(0, 1), List.of(0, 2)),
            result.applications().stream().map(TypedOutputPattern.Application::positions).toList());
        assertThrows(UnsupportedOperationException.class, () -> result.applications().clear());
        var first = result.applications().getFirst();
        assertThrows(UnsupportedOperationException.class, () -> first.positions().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.bindings().clear());
    }

    @Test void neverReusesOnePhysicalOutputForTwoPatternSlots() {
        var candidate = new TypedPatternGeneralizer().generalize(List.of("x", "y").stream().map(name -> {
            Expr x = v(name);
            return new TypedPatternGeneralizer.Example(f("bundle", f("f", x), f("f", x)),
                f("bundle", f("g", x), f("f", x)));
        }).toList()).orElseThrow();
        var pattern = new TypedOutputPattern(candidate);
        assertTrue(pattern.find(f("bundle", f("f", v("x"))), 20).applications().isEmpty());
        var result = pattern.find(f("bundle", f("f", v("x")), f("f", v("x"))), 20);
        assertEquals(2, result.applications().size());
        for (var application : result.applications()) {
            assertNotEquals(application.positions().get(0), application.positions().get(1));
        }
    }

    @Test void reportsBudgetExhaustionInsteadOfFalseAbsence() {
        FunctionExpr input = f("bundle", f("keep", v("z")), f("f", v("z")));
        var bounded = learned().find(input, 1);
        assertFalse(bounded.complete());
        assertTrue(bounded.applications().isEmpty());
        assertEquals(1, bounded.assignmentAttempts());
        var exact = learned().find(input, 2);
        assertTrue(exact.complete(), "using the last permitted attempt need not imply truncation");
        assertEquals(2, exact.assignmentAttempts());
        assertEquals(1, exact.applications().size());
    }

    @Test void rejectsUnsupportedHypothesesRatherThanDroppingSlotsOrInventingBindings() {
        var p = PatternExpr.var("P");
        assertThrows(IllegalArgumentException.class, () -> new TypedOutputPattern(candidate(
            PatternExpr.fn("bundle", p), PatternExpr.fn("other", p))));
        assertThrows(IllegalArgumentException.class, () -> new TypedOutputPattern(candidate(
            PatternExpr.fn("bundle", p, p), PatternExpr.fn("bundle", p))));
        assertThrows(IllegalArgumentException.class, () -> new TypedOutputPattern(candidate(
            PatternExpr.fn("bundle", p), PatternExpr.fn("bundle", PatternExpr.var("MISSING")))));
    }

    @Test void rejectsExcessiveResourcesBeforeMatching() {
        var pattern = learned();
        var ordinary = f("bundle", f("f", v("x")), f("keep", v("x")));
        assertThrows(IllegalArgumentException.class, () -> pattern.find(ordinary, 0));
        assertThrows(IllegalArgumentException.class,
            () -> pattern.find(ordinary, TypedOutputPattern.MAXIMUM_ASSIGNMENTS + 1));
        assertThrows(IllegalArgumentException.class, () -> pattern.find(new FunctionExpr("bundle",
            Collections.nCopies(TypedOutputPattern.MAXIMUM_OUTPUTS + 1, v("x"))), 20));
        Expr deep = v("x");
        for (int i = 0; i <= TypedPatternGeneralizer.MAXIMUM_DEPTH; i++) deep = f("nested", deep);
        FunctionExpr oversized = f("bundle", deep);
        assertThrows(IllegalArgumentException.class, () -> pattern.find(oversized, 20));
    }

    @Test void validNonmatchingEnvelopeIsCompleteWithoutAssignments() {
        var result = learned().find(f("other", f("f", v("x")), f("keep", v("x"))), 20);
        assertTrue(result.complete());
        assertTrue(result.applications().isEmpty());
        assertEquals(0, result.assignmentAttempts());
    }

    private static TypedOutputPattern learned() {
        var observations = List.of("x", "y").stream().map(name -> {
            Expr x = v(name);
            return new TypedPatternGeneralizer.Example(f("bundle", f("f", x), f("keep", x)),
                f("bundle", f("g", x), f("keep", x)), List.of(name + " integer"));
        }).toList();
        return new TypedOutputPattern(new TypedPatternGeneralizer().generalize(observations).orElseThrow());
    }

    private static TypedPatternGeneralizer.Candidate candidate(PatternExpr source, PatternExpr target) {
        return new TypedPatternGeneralizer.Candidate(source, target, List.of(), List.of());
    }

    private static TypedOutputPattern.Application only(TypedOutputPattern.Result result) {
        assertEquals(1, result.applications().size());
        return result.applications().getFirst();
    }

    private static Expr v(String name) { return new VariableExpr(name); }
    private static FunctionExpr f(String name, Expr... arguments) { return new FunctionExpr(name, List.of(arguments)); }
}
