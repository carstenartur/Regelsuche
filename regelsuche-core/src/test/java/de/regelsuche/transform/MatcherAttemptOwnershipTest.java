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
        boolean abortHandoff, sawAttemptWithTrace, sawFailedAttempt, sawOutcome;
        String trace;
        final List<Long> failedDebits = new ArrayList<>();

        @Override public void executionWork(long units) {
            if (failure != null) failedDebits.add(units);
            else if (abortHandoff && scope != null && snapshot().returnedMatch()) {
                failure = new HandoffAbort(); throw failure;
            }
        }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }

        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var snapshot = snapshot();
            sawAttemptWithTrace |= snapshot.returnedMatch() && snapshot.tracedState();
            sawFailedAttempt |= failure != null && snapshot.returnedMatch();
            sawOutcome |= snapshot.outcome();
        }

        private Snapshot snapshot() {
            var pending = new ArrayDeque<Object>();
            var seen = Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value,Class<?> type) { assertEquals(type,value.getClass()); }
            };
            visitor.reference(scope);
            boolean matched = false, lowLevel = false, traced = false, outcome = false;
            while (!pending.isEmpty()) {
                Object value = pending.remove(); if (!seen.add(value)) continue;
                matched |= value instanceof EquivalenceAwarePatternMatcher.MatchAttempt attempt && attempt.matched();
                outcome |= value instanceof ExprMatcher.MatchOutcome;
                lowLevel |= value.getClass().getEnclosingClass() == EquivalenceAwarePatternMatcher.class
                    && value.getClass().getSimpleName().equals("MatchSearch");
                if (value instanceof RetainedGraph.View view) {
                    if (value.getClass().getEnclosingClass() == ExprMatcherEngine.class
                            && value.getClass().getSimpleName().equals("State")) {
                        var references = new ArrayList<Object>();
                        view.retainedReferences(new RetainedGraph.Visitor() {
                            @Override public void reference(Object item) { references.add(item); }
                            @Override public void requireExact(Object item,Class<?> type) { assertEquals(type,item.getClass()); }
                        });
                        traced |= references.stream().anyMatch(item -> item instanceof List<?> list && list.contains(trace));
                    }
                    view.retainedReferences(visitor);
                } else if (value instanceof Object[] array) {
                    for (Object item : array) visitor.reference(item);
                } else if (value instanceof Collection<?> values) values.forEach(visitor::reference);
                else if (value instanceof Map<?,?> map) {
                    map.forEach((key,item) -> { visitor.reference(key); visitor.reference(item); });
                }
            }
            return new Snapshot(matched && !lowLevel,traced,outcome);
        }
    }

    private record Snapshot(boolean returnedMatch,boolean tracedState,boolean outcome) { }

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
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void failedExactHandoffSettlesItsCountersOnce() { verifyAbort(false); }
    @Test void failedEquivalentHandoffSettlesItsRealBranchesOnce() { verifyAbort(true); }

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
