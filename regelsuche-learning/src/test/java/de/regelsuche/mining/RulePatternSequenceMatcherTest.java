package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.mining.RulePatternMatcher.MatchResult;
import de.regelsuche.mining.RulePatternMatcher.MatchStatus;
import de.regelsuche.mining.RulePatternMatcher.MatchStep;
import de.regelsuche.parse.ExpressionFormatter;
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

@Timeout(30)
class RulePatternSequenceMatcherTest {
    private final RulePatternMatcher matcher = new RulePatternMatcher();
    private final RulePatternParser patterns = new RulePatternParser();
    private final ExpressionParser expressions = new ExpressionParser();

    private MatchStep step(String pattern, String expression) {
        return step(pattern, expressions.parseTerm(expression));
    }

    private MatchStep step(String pattern, Expr expression) {
        return new MatchStep(patterns.parse(pattern), expression);
    }

    private MatchResult match(List<MatchStep> steps) {
        return matcher.matchSequence(steps, Map.of(), 10_000);
    }

    @Test
    void laterStepCanRequireAnEarlierCommutativeAlternative() {
        var result = match(List.of(step("A+B", "y+x"), step("A-B", "x-y")));
        assertEquals(MatchStatus.MATCH, result.status());
        assertEquals("x", ExpressionFormatter.format(result.bindings().get("A")));
        assertEquals("y", ExpressionFormatter.format(result.bindings().get("B")));
    }

    @Test
    void allStatesOfACancellationTraceShareOneSubstitution() {
        var result = match(List.of(
            step("(A+B)*(A-B)+B*B", "(y+x)*(x-y)+y*y"),
            step("A^2-B^2+B*B", "x^2-y^2+y*y"),
            step("A^2-B^2+B^2", "x^2-y^2+y^2"),
            step("A^2", "x^2")));
        assertEquals(MatchStatus.MATCH, result.status());
        assertEquals("x", ExpressionFormatter.format(result.bindings().get("A")));
        assertEquals("y", ExpressionFormatter.format(result.bindings().get("B")));
    }

    @Test
    void laterStepsCannotSilentlyRebindAnEarlierPlaceholder() {
        var result = match(List.of(
            step("(A+B)*(A-B)+B*B", "(y+x)*(x-y)+y*y"),
            step("A^2-B^2+B^2", "x^2-y^2+z^2")));
        assertEquals(MatchStatus.NO_MATCH, result.status());
        assertTrue(result.bindings().isEmpty());
    }

    @Test
    void boundSymbolsWithEqualLabelsButDifferentScopesDoNotMatch() {
        var left = new SymbolScope(new UUID(0, 1));
        var right = new SymbolScope(new UUID(0, 2));
        var result = match(List.of(
            step("A", SymbolicExpression.parse("x", left).expression()),
            step("A", SymbolicExpression.parse("x", right).expression())));
        assertEquals(MatchStatus.NO_MATCH, result.status());
        assertTrue(result.bindings().isEmpty());
    }

    @Test
    void aliasesAndDisplayRenamingPreserveTheSharedSymbolBinding() {
        var scope = new SymbolScope(new UUID(0, 1));
        scope.alias("v", scope.resolve("y"));
        var first = SymbolicExpression.parse("y+x", scope);
        var second = SymbolicExpression.parse("x-v", scope).withDisplayName(scope.resolve("y"), "shownY");
        var result = match(List.of(step("A+B", first.expression()), step("A-B", second.expression())));
        assertEquals(MatchStatus.MATCH, result.status());
        assertEquals(scope.resolve("x"), ((VariableExpr) result.bindings().get("A")).symbol().orElseThrow());
        assertEquals(scope.resolve("y"), ((VariableExpr) result.bindings().get("B")).symbol().orElseThrow());
    }

    @Test
    void initialBindingsAreConstraintsAndNeitherInputNorResultIsMutated() {
        Map<String, Expr> initial = new HashMap<>();
        initial.put("A", expressions.parseTerm("x"));
        var result = matcher.matchSequence(List.of(step("A+B", "y+x")), initial, 10_000);
        assertEquals(MatchStatus.MATCH, result.status());
        assertEquals(1, initial.size());
        assertFalse(initial.containsKey("B"));
        initial.clear();
        assertEquals("x", ExpressionFormatter.format(result.bindings().get("A")));
        assertEquals("y", ExpressionFormatter.format(result.bindings().get("B")));
        assertThrows(UnsupportedOperationException.class, () -> result.bindings().clear());
    }

    @Test
    void typedSingleStepOverloadHonorsExistingBindings() {
        var seed = Map.of("A", expressions.parseTerm("x"));
        var result = matcher.matchExpression(patterns.parse("A+B"), expressions.parseTerm("y+x"), seed).orElseThrow();
        assertEquals("x", ExpressionFormatter.format(result.get("A")));
        assertEquals("y", ExpressionFormatter.format(result.get("B")));
        assertTrue(matcher.matchExpression(patterns.parse("A+A"), expressions.parseTerm("y+y"), seed).isEmpty());
        assertEquals(1, seed.size());
    }

    @Test
    void aCutoffDoesNotBecomeNoMatchOrExposePartialBindings() {
        var steps = List.of(step("A+B", "y+x"), step("A-B", "x-y"));
        var completed = match(steps);
        assertEquals(MatchStatus.MATCH, completed.status());
        assertTrue(completed.workUnits() > 1);
        var exact = matcher.matchSequence(steps, Map.of(), completed.workUnits());
        assertEquals(completed, exact);
        var cut = matcher.matchSequence(steps, Map.of(), completed.workUnits() - 1);
        assertEquals(MatchStatus.BUDGET_EXHAUSTED, cut.status());
        assertEquals(completed.workUnits() - 1, cut.workUnits());
        assertTrue(cut.bindings().isEmpty());
    }

    @Test
    void fullyExhaustedFailureHasAnExactWorkBoundaryToo() {
        var steps = List.of(step("A+A", "x+y"));
        var completed = match(steps);
        assertEquals(MatchStatus.NO_MATCH, completed.status());
        assertEquals(completed, matcher.matchSequence(steps, Map.of(), completed.workUnits()));
        assertEquals(MatchStatus.BUDGET_EXHAUSTED,
            matcher.matchSequence(steps, Map.of(), completed.workUnits() - 1).status());
    }

    @Test
    void retainsTheRepeatedAssociativeSpecialCaseAcrossLaterConstraints() {
        var good = match(List.of(step("A*A*A*A", "((x+1)*(x+1))*((x+1)*(x+1))"), step("A", "x+1")));
        assertEquals(MatchStatus.MATCH, good.status());
        assertEquals("x + 1", ExpressionFormatter.format(good.bindings().get("A")));
        var bad = match(List.of(step("A*A*A*A", "((x+1)*(x+1))*((x+1)*(x+2))"), step("A", "x+1")));
        assertEquals(MatchStatus.NO_MATCH, bad.status());
    }

    @Test
    void comparesAllSmallPermutationsToAnIndependentAssignmentOracle() {
        for (String a : List.of("x", "y", "z")) for (String b : List.of("x", "y", "z")) {
            for (String c : List.of("x", "y", "z")) for (String d : List.of("x", "y", "z")) {
                boolean expected = (a.equals(c) && b.equals(d)) || (a.equals(d) && b.equals(c));
                var result = match(List.of(step("A+B", a + "+" + b), step("A-B", c + "-" + d)));
                String input = a + "+" + b + "; " + c + "-" + d;
                assertEquals(expected ? MatchStatus.MATCH : MatchStatus.NO_MATCH, result.status(), input);
                if (expected) {
                    assertEquals(c, ExpressionFormatter.format(result.bindings().get("A")), input);
                    assertEquals(d, ExpressionFormatter.format(result.bindings().get("B")), input);
                } else assertTrue(result.bindings().isEmpty(), input);
            }
        }
    }

    @Test
    void validatesSequenceBoundsAndDoesNotRetainFailedSearchState() {
        var single = step("A", "x");
        assertThrows(IllegalArgumentException.class, () -> matcher.matchSequence(List.of(), Map.of(), 10));
        assertThrows(IllegalArgumentException.class, () -> matcher.matchSequence(Collections.nCopies(65, single), Map.of(), 1_000));
        assertThrows(IllegalArgumentException.class, () -> matcher.matchSequence(List.of(single), Map.of(), 0));
        assertThrows(NullPointerException.class, () -> new MatchStep(null, expressions.parseTerm("x")));
        assertThrows(NullPointerException.class, () -> new MatchStep(patterns.parse("A"), null));
        assertEquals(MatchStatus.MATCH, match(Collections.nCopies(64, single)).status());
        assertEquals(MatchStatus.NO_MATCH, match(List.of(step("A+A", "x+y"))).status());
        assertEquals(MatchStatus.MATCH, match(List.of(single)).status());
    }
}
