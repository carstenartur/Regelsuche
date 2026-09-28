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
        int unpublishedResultScans;
        final Set<String> patternDescriptions = Collections.newSetFromMap(new IdentityHashMap<>());
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
            boolean hasStateList = false;
            while (!pending.isEmpty()) {
                Object value = pending.remove(); if (!seen.add(value)) continue;
                if (value instanceof String text && text.startsWith("7:pattern")) patternDescriptions.add(text);
                sawSession |= value.getClass().getEnclosingClass() == ExprMatcherEngine.class
                    && value.getClass().getSimpleName().equals("Session");
                if (value instanceof ExprMatcher.MatchOutcome result) outcome = result;
                if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Object[] array) for (var item : array) visitor.reference(item);
                else if (value instanceof Collection<?> values) {
                    hasStateList |= values.stream().anyMatch(item -> item != null
                        && item.getClass().getEnclosingClass() == ExprMatcherEngine.class
                        && item.getClass().getSimpleName().equals("State"));
                    values.forEach(visitor::reference);
                }
                else if (value instanceof Map<?,?> map)
                    map.forEach((key,item) -> { visitor.reference(key); visitor.reference(item); });
                else if (value instanceof BinaryExpr binary) {
                    visitor.reference(binary.left()); visitor.reference(binary.right());
                } else if (value instanceof FunctionExpr function) visitor.reference(function.arguments());
            }
            inputMissing |= !seen.contains(input);
            if (hasStateList && outcome == null) unpublishedResultScans++;
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

    @Test void monotonicallyGrowingResultAssemblyUsesItsPublicationObservation() {
        Expr input = new ExpressionParser().parseTerm("f(x+y,y)");
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertTrue(matcher().match(input).matched());
        }
        assertNotNull(observation.outcome,"the complete result and source graph must still be observed");
        assertEquals(0,observation.unpublishedResultScans,
            "retaining the raw/frozen states through monotone result assembly avoids an intermediate full scan");
        assertFalse(observation.inputMissing);
    }

    @Test void anExplicitPatternDescriptionPublishesItsActualOutputText() {
        var observation = new Observation();
        String description;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            description = matcher().canonicalDescriptor();
        }
        assertTrue(observation.patternDescriptions.contains(description),
            "explicit descriptor output must be owned and charged before returning");
        assertTrue(observation.work >= description.length());
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void aBoundedPatternMatchDoesNotConstructUnusedDiagnosticDescriptions() {
        Expr input = new ExpressionParser().parseTerm("f(x+y,y)");
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertTrue(matcher().match(input).matched());
        }
        assertTrue(observation.patternDescriptions.isEmpty(),
            "a successful bounded match needs no canonical diagnostic description");
        assertFalse(observation.inputMissing);
    }

    @Test void aRealStepLimitStillProducesTheSamePaidDescription() {
        Expr input = new ExpressionParser().parseTerm("f(x+y,y)");
        var pattern = matcher(); var expected = pattern.canonicalDescriptor();
        var limited = ExprMatcher.allOf(ExprMatcher.any(),pattern);
        var observation = new Observation(); observation.input = input;
        ExprMatcher.MatchOutcome result;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            result = limited.match(input,new ExprMatcher.MatchOptions(null,64,2,1000));
        }
        assertFalse(result.complete()); assertFalse(result.matched()); assertEquals(2,result.evaluatedSteps());
        assertEquals(new ExprMatcher.MatchDiagnostic("MATCH_STEP_LIMIT",expected),result.diagnostics().getFirst());
        assertTrue(observation.patternDescriptions.contains(result.diagnostics().getFirst().matcherDescriptor()));
        assertFalse(observation.inputMissing);
    }

    @Test void anExhaustedSiblingDoesNotRenderAnotherStepLimitDescription() {
        Expr input = new VariableExpr("x"); var pattern = matcher();
        var expected = new ExprMatcher.MatchDiagnostic("MATCH_STEP_LIMIT",pattern.canonicalDescriptor());
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = ExprMatcher.anyOf(pattern,pattern).match(input,
                new ExprMatcher.MatchOptions(null,64,1,1000));
            assertFalse(result.matched()); assertEquals(1,result.evaluatedSteps());
            assertEquals(List.of(expected),result.diagnostics());
        }
        assertEquals(1,observation.patternDescriptions.size(),
            "after the first step-limit diagnostic, exhausted siblings need no new descriptions");
    }

    @Test void aConstraintStepLimitKeepsItsOwnDescription() {
        Expr input = new VariableExpr("x");
        var constraint = ExprMatcher.bindingMatches("missing",matcher());
        var expected = new ExprMatcher.MatchDiagnostic("MATCH_STEP_LIMIT",constraint.canonicalDescriptor());
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = ExprMatcher.where(ExprMatcher.any(),constraint).match(input,
                new ExprMatcher.MatchOptions(null,64,2,1000));
            assertFalse(result.matched()); assertEquals(2,result.evaluatedSteps());
            assertEquals(List.of(expected),result.diagnostics());
        }
        assertEquals(1,observation.patternDescriptions.size());
    }

    @Test void aRealResultLimitKeepsTheFirstResultAndItsExactDescription() {
        Expr input = new ExpressionParser().parseTerm("f(x+y,y)");
        var limited = ExprMatcher.anyOf(matcher(),matcher());
        var expected = new ExprMatcher.MatchDiagnostic("MATCH_RESULT_LIMIT",limited.canonicalDescriptor());
        var first = matcher().match(input).matches().getFirst();
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = limited.match(input,new ExprMatcher.MatchOptions(null,1,64,1000));
            assertEquals(List.of(first),result.matches());
            assertEquals(List.of(expected),result.diagnostics());
            assertFalse(result.complete());
        }
        assertEquals(2,observation.patternDescriptions.size(),
            "the actual result-limit description contains both children, each rendered once");
        assertFalse(observation.inputMissing);
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
