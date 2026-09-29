package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.util.*;
import org.junit.jupiter.api.Test;

class MatcherAttemptOwnershipTest {
    private static final class HandoffAbort extends RuntimeException { }

    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        HandoffAbort failure;
        boolean abortHandoff, abortResult, abortResultClose, sawAttemptWithTrace, sawFailedAttempt, sawOutcome;
        boolean sawAttemptWithResultList, sawFailedResultList;
        boolean abortSingle, abortExcluded, sawSingleHandoff, sawExcludedResult;
        String trace;
        final List<Long> failedDebits = new ArrayList<>();

        @Override public void executionWork(long units) {
            if (failure != null) failedDebits.add(units);
            else if (scope != null) {
                var snapshot = snapshot();
                if ((abortHandoff && snapshot.returnedMatch())
                        || (abortResult && snapshot.returnedMatch() && snapshot.resultList())
                        || (abortResultClose && sawAttemptWithResultList && units == 4)
                        || (abortSingle && snapshot.singletonHandoff())
                        || (abortExcluded && snapshot.excludedResult())) {
                    failure = new HandoffAbort(); throw failure;
                }
            }
        }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }

        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var snapshot = snapshot();
            sawAttemptWithTrace |= snapshot.returnedMatch() && snapshot.tracedState();
            sawAttemptWithResultList |= snapshot.returnedMatch() && snapshot.resultList();
            sawFailedAttempt |= failure != null && snapshot.returnedMatch();
            sawFailedResultList |= failure != null && snapshot.resultList();
            sawOutcome |= snapshot.outcome();
            sawSingleHandoff |= snapshot.singletonHandoff();
            sawExcludedResult |= snapshot.excludedResult();
        }

        private Snapshot snapshot() {
            var pending = new ArrayDeque<Object>();
            var seen = Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value,Class<?> type) { assertEquals(type,value.getClass()); }
            };
            visitor.reference(scope);
            boolean matched = false, lowLevel = false, traced = false, outcome = false, resultList = false;
            while (!pending.isEmpty()) {
                Object value = pending.remove(); if (!seen.add(value)) continue;
                matched |= value instanceof EquivalenceAwarePatternMatcher.MatchAttempt attempt && attempt.matched();
                outcome |= value instanceof ExprMatcher.MatchOutcome;
                lowLevel |= value.getClass().getEnclosingClass() == EquivalenceAwarePatternMatcher.class
                    && value.getClass().getSimpleName().equals("MatchSearch");
                if (value instanceof RetainedGraph.View view) {
                    traced |= stateHasTrace(value,trace);
                    view.retainedReferences(visitor);
                } else if (value instanceof Object[] array) {
                    for (Object item : array) visitor.reference(item);
                } else if (value instanceof Collection<?> values) {
                    resultList |= values instanceof List<?> && values.stream().anyMatch(item -> item != null
                        && item.getClass().getEnclosingClass() == ExprMatcherEngine.class
                        && item.getClass().getSimpleName().equals("State"));
                    values.forEach(visitor::reference);
                }
                else if (value instanceof Map<?,?> map) {
                    map.forEach((key,item) -> { visitor.reference(key); visitor.reference(item); });
                }
            }
            return new Snapshot(matched && !lowLevel,traced,outcome,resultList,singletonHandoff(seen),excludedResult(seen));
        }

        private boolean singletonHandoff(Set<Object> graph) {
            if (trace == null) return false;
            var adopted = Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            for (Object owner : graph) {
                if (matcherType(owner,"Session") || matcherType(owner,"StateLists")) {
                    for (Object reference : references((RetainedGraph.View) owner)) {
                        adopted.add(reference);
                        if (reference instanceof List<?> list) adopted.addAll(list);
                    }
                }
            }
            return graph.stream().anyMatch(value -> value instanceof List<?> list && list.size() == 1
                && stateHasTrace(list.getFirst(),trace) && !adopted.contains(list) && !adopted.contains(list.getFirst()));
        }

        private boolean excludedResult(Set<Object> graph) {
            return graph.stream().filter(value -> matcherType(value,"StateLists"))
                .flatMap(value -> references((RetainedGraph.View) value).stream())
                .anyMatch(value -> value instanceof List<?> list && list.size() == 1
                    && stateHasTrace(list.getFirst(),"any"));
        }
    }

    private record Snapshot(boolean returnedMatch,boolean tracedState,boolean outcome,boolean resultList,
                            boolean singletonHandoff,boolean excludedResult) { }

    private static boolean matcherType(Object value,String name) {
        return value != null && value.getClass().getEnclosingClass() == ExprMatcherEngine.class
            && value.getClass().getSimpleName().equals(name);
    }
    private static boolean stateHasTrace(Object value,String trace) {
        return trace != null && matcherType(value,"State") && references((RetainedGraph.View) value).stream()
            .anyMatch(item -> item instanceof List<?> list && list.contains(trace));
    }
    private static List<Object> references(RetainedGraph.View view) {
        var values = new ArrayList<Object>();
        view.retainedReferences(new RetainedGraph.Visitor() {
            @Override public void reference(Object value) { values.add(value); }
            @Override public void requireExact(Object value,Class<?> type) { assertEquals(type,value.getClass()); }
        });
        return values;
    }

    @Test void anyPublishesItsActualSingletonBeforeCallerAdoption() {
        verifySingleton(ExprMatcher.any(),"x","any");
    }
    @Test void literalNumberPublishesItsActualSingletonBeforeCallerAdoption() {
        verifySingleton(ExprMatcher.literalNumber(3),"3","literal-number");
    }
    @Test void literalVariablePublishesItsActualSingletonBeforeCallerAdoption() {
        verifySingleton(ExprMatcher.literalVariable("x"),"x","literal-variable");
    }
    @Test void numberPropertyPublishesItsActualSingletonBeforeCallerAdoption() {
        verifySingleton(ExprMatcher.integerLiteral(),"3","number-property:INTEGER_LITERAL");
    }
    @Test void successfulNegationPublishesItsActualSingletonBeforeCallerAdoption() {
        verifySingleton(ExprMatcher.not(ExprMatcher.literalVariable("y")),"x","not");
    }
    @Test void sameAsPublishesItsActualSingletonBeforeCallerAdoption() {
        var matcher = ExprMatcher.where(ExprMatcher.allOf(ExprMatcher.bind("A",ExprMatcher.any()),
            ExprMatcher.bind("B",ExprMatcher.any())),ExprMatcher.sameAs("A","B"));
        verifySingleton(matcher,"x","same-as");
    }
    private static void verifySingleton(ExprMatcher matcher,String source,String trace) {
        Expr input = new ExpressionParser().parseTerm(source);
        var expected = matcher.match(input); assertTrue(expected.matched()); assertTrue(expected.complete());
        var observation = new Observation(); observation.trace = trace;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope; assertEquals(expected,matcher.match(input));
        }
        assertTrue(observation.sawSingleHandoff,"the actual trace-bearing singleton must be owned before its caller adopts it");
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
    @Test void failedSingletonPublicationObservesItsListAndSettlesTheUnreturnedStepOnce() {
        var observation = new Observation(); observation.trace = "any"; observation.abortSingle = true;
        verifyDirectAbort(ExprMatcher.any(),observation,1);
        assertTrue(observation.sawSingleHandoff);
    }
    @Test void negationOwnsTheReturnedExcludedResultDuringItsDecision() {
        var observation = new Observation();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = ExprMatcher.not(ExprMatcher.any()).match(new ExpressionParser().parseTerm("x"));
            assertFalse(result.matched()); assertTrue(result.complete()); assertEquals(2,result.evaluatedSteps());
        }
        assertTrue(observation.sawExcludedResult);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
    @Test void failedNegationDecisionKeepsItsExcludedResultAndSettlesBothStepsOnce() {
        var observation = new Observation(); observation.abortExcluded = true;
        verifyDirectAbort(ExprMatcher.not(ExprMatcher.any()),observation,2);
        assertTrue(observation.sawExcludedResult);
    }
    private static void verifyDirectAbort(ExprMatcher matcher,Observation observation,long delegated) {
        Expr input = new ExpressionParser().parseTerm("x");
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(HandoffAbort.class,() -> matcher.match(input));
            assertSame(observation.failure,failure); assertFalse(observation.sawOutcome);
            assertEquals(delegated,observation.failedDebits.getLast());
            assertEquals(1,observation.failedDebits.stream().filter(value -> value == delegated).count());
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
    @Test void negationPreservesInconclusiveChildrenAndNestedNegation() {
        Expr input = new ExpressionParser().parseTerm("x");
        var limited = ExprMatcher.not(ExprMatcher.any()).match(input,new ExprMatcher.MatchOptions(null,64,1,100));
        assertFalse(limited.matched()); assertFalse(limited.complete());
        assertEquals(List.of("MATCH_STEP_LIMIT"),limited.diagnostics().stream().map(ExprMatcher.MatchDiagnostic::code).toList());
        var nested = ExprMatcher.not(ExprMatcher.not(ExprMatcher.any())).match(input);
        assertTrue(nested.matched()); assertTrue(nested.complete()); assertEquals(3,nested.evaluatedSteps());
        assertEquals(List.of("not"),nested.matches().getFirst().trace());
    }

    private static ExprMatcher matcher(boolean reorder) {
        return reorder
            ? ExprMatcher.pattern(PatternExpr.op(ADD,PatternExpr.var("A"),PatternExpr.num(0)),RecognitionProfile.arithmeticAc())
            : ExprMatcher.pattern(PatternExpr.var("A"));
    }

    @Test void exactAttemptRemainsOwnedWhileItsStateIsTraced() { verifyHandoff(false); }
    @Test void equivalentAttemptRemainsOwnedWhileItsStateIsTraced() { verifyHandoff(true); }

    private static void verifyHandoff(boolean reorder) {
        Expr input = new ExpressionParser().parseTerm("0+x"); var matcher = matcher(reorder);
        var expected = matcher.match(input);
        assertTrue(expected.matched()); assertTrue(expected.complete());
        assertEquals(reorder,expected.patternBranches() > 0);
        var observation = new Observation(); observation.trace = reorder ? "pattern:equivalence-aware" : "pattern:exact";
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertEquals(expected,matcher.match(input));
        }
        assertTrue(observation.sawAttemptWithTrace,"the actual returned attempt must overlap the derived State");
        assertTrue(observation.sawAttemptWithResultList,"the produced result list must be owned before the frame can close");
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void failedExactHandoffSettlesItsCountersOnce() { verifyAbort(false); }
    @Test void failedEquivalentHandoffSettlesItsRealBranchesOnce() { verifyAbort(true); }

    @Test void failedResultPublicationObservesTheProducedListBeforeRelease() { verifyResultAbort(false); }
    @Test void failedResultFrameCloseSettlesItsCountersOnce() { verifyResultAbort(true); }

    private static void verifyResultAbort(boolean close) {
        Expr input = new ExpressionParser().parseTerm("0+x"); var matcher = matcher(true);
        var expected = matcher.match(input);
        var observation = new Observation(); observation.abortResult = !close; observation.abortResultClose = close;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(HandoffAbort.class,() -> matcher.match(input));
            assertSame(observation.failure,failure);
            assertTrue(observation.sawAttemptWithResultList);
            assertEquals(!close,observation.sawFailedResultList);
            assertFalse(observation.sawOutcome);
            long delegated = (long) expected.evaluatedSteps() + expected.patternBranches();
            assertEquals(close ? List.of(4L,delegated) : List.of(4L,4L,delegated),observation.failedDebits);
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    private static void verifyAbort(boolean reorder) {
        Expr input = new ExpressionParser().parseTerm("0+x"); var matcher = matcher(reorder);
        var expected = matcher.match(input);
        assertEquals(reorder,expected.patternBranches() > 0);
        var observation = new Observation(); observation.abortHandoff = true;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(HandoffAbort.class,() -> matcher.match(input));
            assertSame(observation.failure,failure);
            assertTrue(observation.sawFailedAttempt); assertFalse(observation.sawOutcome);
            assertEquals(List.of(4L,4L,(long) expected.evaluatedSteps() + expected.patternBranches()),observation.failedDebits,
                "only the two actual frame closures and one transfer of unreturned counters remain");
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
}
