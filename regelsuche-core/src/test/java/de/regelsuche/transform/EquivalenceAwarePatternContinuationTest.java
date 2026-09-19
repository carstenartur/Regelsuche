package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EquivalenceAwarePatternContinuationTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final PatternExpr a = PatternExpr.var("A");
    private final PatternExpr b = PatternExpr.var("B");
    private final PatternExpr product = PatternExpr.op(BinaryOperator.MUL, a, b);
    private final RecognitionProfile ac = RecognitionProfile.arithmeticAc();

    @Test
    void laterFunctionArgumentCanRejectAndReopenAnEarlierProductChoice() {
        var pattern = PatternExpr.fn("f", product, b);
        var input = parser.parseTerm("f(x*y,x)");
        var attempt = EquivalenceAwarePatternMatcher.matchDetailed(pattern, input, Map.of(), ac);
        assertTrue(attempt.matched());
        assertEquals(parser.parseTerm("y"), attempt.bindings().get("A"));
        assertEquals(parser.parseTerm("x"), attempt.bindings().get("B"));
        assertTrue(ExprMatcher.pattern(pattern, ac).match(input).matched());
        assertFalse(ExprMatcher.pattern(pattern).match(input).matched());
    }

    @Test
    void rightOperandOfNonCommutativeOperatorConstrainsAnEarlierChoice() {
        var pattern = PatternExpr.op(BinaryOperator.SUB, product, b);
        var attempt = EquivalenceAwarePatternMatcher.matchDetailed(pattern,
            parser.parseTerm("x*y-x"), Map.of(), ac);
        assertTrue(attempt.matched());
        assertEquals(parser.parseTerm("x"), attempt.bindings().get("B"));
    }

    @Test
    void anOuterCommutativeContinuationCanReopenANestedChoice() {
        var pattern = PatternExpr.op(BinaryOperator.ADD, PatternExpr.fn("g", product), b);
        var attempt = EquivalenceAwarePatternMatcher.matchDetailed(pattern,
            parser.parseTerm("g(x*y)+x"), Map.of(), ac);
        assertTrue(attempt.matched());
        assertEquals(parser.parseTerm("y"), attempt.bindings().get("A"));
        assertEquals(parser.parseTerm("x"), attempt.bindings().get("B"));
    }

    @Test
    void exhaustedContinuationBudgetIsInconclusiveAndDoesNotLeakBindings() {
        var seed = Map.<String, Expr>of("unrelated", new VariableExpr("z"));
        var pattern = PatternExpr.fn("f", product, b);
        var input = parser.parseTerm("f(x*y,x)");
        var limited = EquivalenceAwarePatternMatcher.matchDetailed(pattern, input, seed, ac, 3);
        assertTrue(limited.inconclusive());
        assertEquals("COMMUTATIVE_BACKTRACKING_LIMIT", limited.limitCode());
        assertEquals(3, limited.visitedBranches());
        assertEquals(seed, limited.bindings());
        var complete = EquivalenceAwarePatternMatcher.matchDetailed(pattern, input, seed, ac, 4);
        assertTrue(complete.matched());
        assertEquals(4, complete.visitedBranches());
        assertEquals(seed.get("unrelated"), complete.bindings().get("unrelated"));
    }

    @Test
    void allFailedContinuationsLeaveTheCallerUnchangedAndReportRealAbsence() {
        var bindings = new HashMap<String, Expr>();
        bindings.put("unrelated", new VariableExpr("z"));
        var before = Map.copyOf(bindings);
        var pattern = PatternExpr.fn("f", product, b);
        assertFalse(EquivalenceAwarePatternMatcher.match(pattern, parser.parseTerm("f(x*y,z)"), bindings, ac));
        assertEquals(before, bindings);
        var detailed = EquivalenceAwarePatternMatcher.matchDetailed(pattern,
            parser.parseTerm("f(x*y,z)"), bindings, ac);
        assertEquals(EquivalenceAwarePatternMatcher.AttemptStatus.NOT_MATCHED, detailed.status());
        assertEquals(before, detailed.bindings());
    }

    @Test
    void anAlreadyCompatibleFirstChoiceKeepsItsOrderingAndCost() {
        var attempt = EquivalenceAwarePatternMatcher.matchDetailed(PatternExpr.fn("f", product, b),
            parser.parseTerm("f(x*y,y)"), Map.of(), ac);
        assertTrue(attempt.matched());
        assertEquals(parser.parseTerm("x"), attempt.bindings().get("A"));
        assertEquals(parser.parseTerm("y"), attempt.bindings().get("B"));
        assertEquals(2, attempt.visitedBranches());
    }

    @Test
    void wideOrderedFunctionsDoNotRequireOneJavaStackFramePerArgument() {
        var pattern = new PatternExpr.Function("wide", java.util.Collections.nCopies(4096, a));
        var input = new FunctionExpr("wide", java.util.Collections.nCopies(4096, new VariableExpr("x")));
        var attempt = EquivalenceAwarePatternMatcher.matchDetailed(pattern, input, Map.of(), ac);
        assertTrue(attempt.matched());
        assertEquals(Map.of("A", new VariableExpr("x")), attempt.bindings());
    }
}
