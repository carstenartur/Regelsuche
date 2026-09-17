package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.mining.RulePatternMatcher.MatchResult;
import de.regelsuche.mining.RulePatternMatcher.MatchStatus;
import de.regelsuche.mining.RulePatternMatcher.MatchStep;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Differential controls retain the historical matcher; independent assertions prevent shared-oracle mistakes. */
@Timeout(30)
class RulePatternLazySequenceMatcherTest {
    private final RulePatternMatcher matcher = new RulePatternMatcher();
    private final RulePatternParser patterns = new RulePatternParser();
    private final ExpressionParser expressions = new ExpressionParser();

    private MatchStep step(String pattern, String expression) {
        return new MatchStep(patterns.parse(pattern), expressions.parseTerm(expression));
    }

    private MatchResult lazy(List<MatchStep> steps, Map<String, Expr> seed, long budget) {
        return matcher.matchSequenceLazy(steps, seed, budget);
    }

    @Test void directSuccessDoesNotConstructUnusedAlternativeConstraints() {
        var steps = List.of(step("A+B", "x+y"));
        var old = matcher.matchSequence(steps, Map.of(), 100);
        var result = lazy(steps, Map.of(), 100);
        assertEquals(MatchStatus.MATCH, result.status());
        assertEquals(Map.of("A", expressions.parseTerm("x"), "B", expressions.parseTerm("y")), result.bindings());
        assertEquals(9, old.workUnits(), "historical work remains an executable control");
        assertEquals(6, result.workUnits(), "one initial constraint, three attempts and two actual child constraints");
        assertTrue(result.workUnits() < old.workUnits());
    }

    @Test void laterConstraintStillRevisitsEarlierSwappedChoicesInTheSameOrder() {
        var steps = List.of(step("A+B", "y+x"), step("A-B", "x-y"));
        var result = lazy(steps, Map.of(), 10_000);
        assertEquals(MatchStatus.MATCH, result.status());
        assertEquals(expressions.parseTerm("x"), result.bindings().get("A"));
        assertEquals(expressions.parseTerm("y"), result.bindings().get("B"));
        assertEquals(matcher.matchSequence(steps, Map.of(), 10_000).bindings(), result.bindings());
    }

    @Test void repeatedAssociativeFallbackIsDeferredNotRemoved() {
        var good = List.of(step("A*A*A*A", "((x+1)*(x+1))*((x+1)*(x+1))"), step("A", "x+1"));
        var bad = List.of(step("A*A*A*A", "((x+1)*(x+1))*((x+1)*(x+2))"), step("A", "x+1"));
        assertEquals(MatchStatus.MATCH, lazy(good, Map.of(), 100_000).status());
        assertEquals(expressions.parseTerm("x+1"), lazy(good, Map.of(), 100_000).bindings().get("A"));
        assertEquals(MatchStatus.NO_MATCH, lazy(bad, Map.of(), 100_000).status());
        for (var steps : List.of(good, bad)) assertSameAnswerWithinBudgets(steps, Map.of());
    }

    @Test void seededExactLiteralsFunctionOrderAndScopedIdentityRemainConstraints() {
        var seed = new HashMap<String, Expr>();
        seed.put("K", expressions.parseTerm("10000000000000000.125"));
        var good = List.of(step("f(A+K,A)", "f(10000000000000000.125+x,x)"));
        assertEquals(MatchStatus.MATCH, lazy(good, seed, 100_000).status());
        assertSameAnswerWithinBudgets(good, seed);
        for (String bad : List.of("f(10000000000000000.126+x,x)", "f(10000000000000000.125+x,y)",
                "f(x,10000000000000000.125+x)", "g(10000000000000000.125+x,x)")) {
            var steps = List.of(step("f(A+K,A)", bad));
            assertEquals(MatchStatus.NO_MATCH, lazy(steps, seed, 100_000).status());
            assertSameAnswerWithinBudgets(steps, seed);
        }
        assertEquals(1, seed.size());
        var result = lazy(good, seed, 100_000);
        seed.clear();
        assertEquals(2, result.bindings().size());
        assertThrows(UnsupportedOperationException.class, () -> result.bindings().clear());
        var first = new SymbolScope(new UUID(0, 1));
        var second = new SymbolScope(new UUID(0, 2));
        var scoped = List.of(new MatchStep(patterns.parse("A"), SymbolicExpression.parse("x", first).expression()),
            new MatchStep(patterns.parse("A"), SymbolicExpression.parse("x", second).expression()));
        assertEquals(MatchStatus.NO_MATCH, lazy(scoped, Map.of(), 100).status());
        assertSameAnswerWithinBudgets(scoped, Map.of());
    }

    @Test void allSmallAssignmentsAgreeWithAnIndependentOracleAndEveryBudgetBoundary() {
        for (String a : List.of("x", "y", "z")) for (String b : List.of("x", "y", "z")) {
            for (String c : List.of("x", "y", "z")) for (String d : List.of("x", "y", "z")) {
                var steps = List.of(step("A+B", a+"+"+b), step("A-B", c+"-"+d));
                boolean expected = (a.equals(c) && b.equals(d)) || (b.equals(c) && a.equals(d));
                var result = lazy(steps, Map.of(), 100_000);
                assertEquals(expected ? MatchStatus.MATCH : MatchStatus.NO_MATCH, result.status());
                if (expected) assertEquals(Map.of("A", expressions.parseTerm(c), "B", expressions.parseTerm(d)), result.bindings());
                assertSameAnswerWithinBudgets(steps, Map.of());
            }
        }
    }

    @Test void nestedChoicesNeverLeakTentativeBindingsOrChangeTheFirstCompleteAnswer() {
        var patternInputs = List.of("(A+B)*(A-B)+B*B", "A+A+A+A", "A*(B+C)", "f(A+B,C+A)", "(A+B)+(A+B)");
        var expressionInputs = List.of("(x+y)*(x-y)+y*y", "(y+x)*(x-y)+z*z", "(x+x)+(x+x)",
            "x*(y+z)", "(y+z)*x", "f(y+x,z+x)", "f(y+x,z+y)", "(x+y)+(y+x)");
        for (var pattern : patternInputs) for (var expression : expressionInputs) {
            var steps = List.of(step(pattern, expression), step("A", "x"));
            assertSameAnswerWithinBudgets(steps, Map.of());
        }
    }

    @Test void validationAndInvocationIsolationMatchTheHistoricalEntryPoint() {
        var single = step("A", "x");
        assertThrows(IllegalArgumentException.class, () -> lazy(List.of(), Map.of(), 10));
        assertThrows(IllegalArgumentException.class, () -> lazy(Collections.nCopies(65, single), Map.of(), 10));
        assertThrows(IllegalArgumentException.class, () -> lazy(List.of(single), Map.of(), 0));
        assertThrows(NullPointerException.class, () -> lazy(null, Map.of(), 10));
        assertThrows(NullPointerException.class, () -> lazy(List.of(single), null, 10));
        assertEquals(MatchStatus.MATCH, lazy(Collections.nCopies(64, single), Map.of(), 1_000).status());
        assertEquals(MatchStatus.BUDGET_EXHAUSTED, lazy(List.of(step("A+B", "x+y")), Map.of(), 1).status());
        assertEquals(MatchStatus.NO_MATCH, lazy(List.of(step("A+A", "x+y")), Map.of(), 1_000).status());
        assertEquals(MatchStatus.MATCH, lazy(List.of(single), Map.of(), 10).status());
    }

    private void assertSameAnswerWithinBudgets(List<MatchStep> steps, Map<String, Expr> seed) {
        var old = matcher.matchSequence(steps, seed, 1_000_000);
        var completed = lazy(steps, seed, 1_000_000);
        assertNotEquals(MatchStatus.BUDGET_EXHAUSTED, old.status(), "differential control must finish");
        assertEquals(old.status(), completed.status());
        assertEquals(old.bindings(), completed.bindings());
        assertTrue(completed.workUnits() <= old.workUnits(), "no more constraints materialized along the same DFS path");
        for (long budget = 1; budget <= old.workUnits(); budget++) {
            var result = lazy(steps, seed, budget);
            assertTrue(result.workUnits() <= budget);
            if (budget < completed.workUnits()) {
                assertEquals(MatchStatus.BUDGET_EXHAUSTED, result.status());
                assertTrue(result.bindings().isEmpty(), "cutoff never exposes tentative bindings");
                assertEquals(budget, result.workUnits());
            } else assertEquals(completed, result);
        }
    }
}
