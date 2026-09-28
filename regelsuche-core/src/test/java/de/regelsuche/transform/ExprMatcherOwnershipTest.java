package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.retention.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExprMatcherOwnershipTest {
    private static final class MatchAbort extends RuntimeException { }
    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        Expr input;
        ExprMatcher.MatchOutcome outcome;
        long work, workAfterFailure;
        MatchAbort failure;
        boolean abortOutcome, abortClose, sawSession, inputMissing;
        @Override public void executionWork(long units) {
            work += units;
            if (failure != null) workAfterFailure += units;
            else if (abortClose && outcome != null && units == 4) {
                failure = new MatchAbort(); throw failure;
            }
        }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }
        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var pending = new ArrayDeque<Object>();
            var seen = Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value, Class<?> type) { assertEquals(type,value.getClass()); }
            };
            visitor.reference(scope);
            while (!pending.isEmpty()) {
                Object value = pending.remove(); if (!seen.add(value)) continue;
                sawSession |= value.getClass().getEnclosingClass() == ExprMatcherEngine.class
                    && value.getClass().getSimpleName().equals("Session");
                if (value instanceof ExprMatcher.MatchOutcome result) outcome = result;
                if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Object[] array) for (var item : array) visitor.reference(item);
                else if (value instanceof Collection<?> values) values.forEach(visitor::reference);
                else if (value instanceof Map<?,?> map)
                    map.forEach((key,item) -> { visitor.reference(key); visitor.reference(item); });
                else if (value instanceof BinaryExpr binary) {
                    visitor.reference(binary.left()); visitor.reference(binary.right());
                } else if (value instanceof FunctionExpr function) visitor.reference(function.arguments());
            }
            inputMissing |= !seen.contains(input);
            if (abortOutcome && outcome != null && failure == null) {
                failure = new MatchAbort(); throw failure;
            }
        }
    }

    private static ExprMatcher matcher() {
        return ExprMatcher.pattern(PatternExpr.fn("f",
            PatternExpr.op(ADD,PatternExpr.var("A"),PatternExpr.var("B")),PatternExpr.var("A")),
            RecognitionProfile.arithmeticAc());
    }

    @Test void matcherAlgebraOptionsAndConstraintsExposeTheirActualReferences() {
        var any = ExprMatcher.any(); var profile = RecognitionProfile.arithmeticAc();
        var definitions = List.of(any,ExprMatcher.literalNumber(2),ExprMatcher.literalVariable("x"),
            ExprMatcher.integerLiteral(),matcher(),ExprMatcher.bind("A",any),ExprMatcher.allOf(any,any),
            ExprMatcher.anyOf(any,any),ExprMatcher.not(any),ExprMatcher.op(ADD,any,any),
            ExprMatcher.fn("f",any),ExprMatcher.contains(any),ExprMatcher.equivalent(profile,any),
            ExprMatcher.where(any,ExprMatcher.sameAs("A","B")),ExprMatcher.bindingMatches("A",any),
            ExprMatcher.MatchOptions.defaults());
        assertDoesNotThrow(() -> RetainedGraph.measure(definitions));
    }

    @Test void sessionAndIndependentOutcomeRemainOwnedThroughResultAssembly() {
        Expr input = new ExpressionParser().parseTerm("f(x+y,y)");
        var expected = matcher().match(input);
        var observation = new Observation(); observation.input = input;
        ExprMatcher.MatchOutcome result;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            result = matcher().match(input);
        }
        assertEquals(expected,result);
        assertTrue(observation.sawSession,"the actual matcher session must be an owner during execution");
        assertSame(result,observation.outcome,"the actual returned result must be observed before release");
        assertFalse(observation.inputMissing);
        assertDoesNotThrow(() -> RetainedGraph.measure(result));
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void diagnosticResultsRemainIndependentlyDescribable() {
        var result = ExprMatcher.anyOf(ExprMatcher.any(),ExprMatcher.any()).match(new VariableExpr("x"),
            new ExprMatcher.MatchOptions(null,1,10,10));
        assertTrue(result.matched()); assertFalse(result.complete());
        assertEquals("MATCH_RESULT_LIMIT",result.diagnostics().getFirst().code());
        assertDoesNotThrow(() -> RetainedGraph.measure(result));
    }

    @Test void abortedOutcomePaysItsUnreturnedStepsAndNestedBranchCounters() {
        verifyFailedHandoff(false);
    }

    @Test void failedFrameCloseAlsoPaysUnreturnedCountersExactlyOnce() {
        verifyFailedHandoff(true);
    }

    private static void verifyFailedHandoff(boolean close) {
        Expr input = new ExpressionParser().parseTerm("f(x+y,y)");
        var observation = new Observation(); observation.input = input;
        observation.abortClose = close; observation.abortOutcome = !close;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(MatchAbort.class,() -> matcher().match(input));
            assertSame(observation.failure,failure);
            assertNotNull(observation.outcome);
            assertTrue(observation.outcome.patternBranches() > 2);
            long delegated = (long) observation.outcome.evaluatedSteps() + observation.outcome.patternBranches();
            assertEquals(delegated + (close ? 0 : 4),observation.workAfterFailure,
                "settle completed unreturned counters once, in addition to actual frame cleanup");
            assertFalse(observation.inputMissing);
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void anOpaqueRepresentativeCaptureIsNotReportedAsMeasuredZero() {
        Expr input = new VariableExpr("x"); var calls = new AtomicInteger();
        EquivalentExpressionProvider opaque = (expression,profile) -> { calls.incrementAndGet(); return List.of(expression); };
        var options = new ExprMatcher.MatchOptions(opaque,1,10,10);
        var matcher = ExprMatcher.equivalent(RecognitionProfile.exact(),ExprMatcher.any());
        assertTrue(matcher.match(input,options).matched()); assertEquals(1,calls.get());
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertThrows(RetainedGraph.Unmeasured.class,() -> matcher.match(input,options));
            assertEquals(1,calls.get(),"unsupported native ownership must fail before invoking its capture");
        }
    }
}
