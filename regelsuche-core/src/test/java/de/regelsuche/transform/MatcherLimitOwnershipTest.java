package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.util.*;
import org.junit.jupiter.api.Test;

class MatcherLimitOwnershipTest {
    private static final class LimitAbort extends RuntimeException { }

    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        LimitAbort failure;
        boolean abortPrefix, sawPrefix, sawAllOwners, sawOutcome;
        long work, workAfterFailure, retentionWork;

        @Override public void executionWork(long units) {
            work += units;
            if (failure != null) workAfterFailure += units;
            else if (abortPrefix && scope != null && snapshot().prefixBeforeFreeze()) {
                failure = new LimitAbort(); throw failure;
            }
        }

        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }

        @Override public void checkpoint() {
            retentionWork += RetainedGraph.measure(scope).work();
            var snapshot = snapshot();
            sawPrefix |= snapshot.prefix();
            sawAllOwners |= snapshot.allOwners();
            sawOutcome |= snapshot.outcome();
        }

        // The injector inspects actual references; it creates no production
        // owner and uses no reflective access to matcher implementation fields.
        private Snapshot snapshot() {
            var pending = new ArrayDeque<Object>();
            var seen = Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var lists = new ArrayList<List<?>>();
            var prefixes = new ArrayList<Object>();
            boolean outcome = false;
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value,Class<?> type) { assertEquals(type,value.getClass()); }
            };
            visitor.reference(scope);
            while (!pending.isEmpty()) {
                Object value = pending.remove(); if (!seen.add(value)) continue;
                outcome |= value instanceof ExprMatcher.MatchOutcome;
                if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Object[] array) {
                    if (array.length == 1 && isState(array[0])) prefixes.add(array[0]);
                    for (Object item : array) visitor.reference(item);
                } else if (value instanceof Collection<?> values) {
                    if (value instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(MatcherLimitOwnershipTest::isState)) {
                        lists.add(list);
                        if (list instanceof ArrayList<?> && list.size() == 1) prefixes.add(list.getFirst());
                    }
                    values.forEach(visitor::reference);
                } else if (value instanceof Map<?,?> map) {
                    map.forEach((key,item) -> { visitor.reference(key); visitor.reference(item); });
                }
            }
            boolean prefix = false, allOwners = false;
            for (List<?> source : lists) {
                if (source.size() != 2) continue;
                for (Object first : prefixes) {
                    if (source.getFirst() != first) continue;
                    prefix = true;
                    allOwners |= lists.stream().anyMatch(result -> !(result instanceof ArrayList<?>)
                        && result.size() == 1 && result.getFirst() == first);
                }
            }
            return new Snapshot(prefix,allOwners,outcome);
        }
    }

    private record Snapshot(boolean prefix,boolean allOwners,boolean outcome) {
        boolean prefixBeforeFreeze() { return prefix && !allOwners; }
    }

    private static boolean isState(Object value) {
        return value != null && value.getClass().getEnclosingClass() == ExprMatcherEngine.class
            && value.getClass().getSimpleName().equals("State");
    }

    private static ExprMatcher alternatives() {
        return ExprMatcher.anyOf(ExprMatcher.any(),ExprMatcher.literalVariable("x"));
    }

    @Test void boundedPrefixOverlapsItsOriginalAndFrozenResult() {
        var matcher = alternatives(); var input = new VariableExpr("x");
        var diagnostic = new ExprMatcher.MatchDiagnostic("MATCH_RESULT_LIMIT",matcher.canonicalDescriptor());
        var observation = new Observation();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = matcher.match(input,new ExprMatcher.MatchOptions(null,1,100,100));
            assertEquals(1,result.matches().size());
            assertEquals(List.of("any"),result.matches().getFirst().trace());
            assertEquals(List.of(diagnostic),result.diagnostics());
            assertFalse(result.complete());
        }
        assertTrue(observation.sawAllOwners,"retain the actual prefix, larger source and frozen result together");
        assertTrue(observation.work > 0); assertTrue(observation.retentionWork > 0);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void prefixDebitFailureIsObservedBeforeReleaseAndCannotReturnAnOutcome() {
        var observation = new Observation(); observation.abortPrefix = true;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(LimitAbort.class,() -> alternatives().match(new VariableExpr("x"),
                new ExprMatcher.MatchOptions(null,1,100,100)));
            assertSame(observation.failure,failure);
            assertTrue(observation.sawPrefix,"failed prefix production must still be observed");
            assertFalse(observation.sawAllOwners,"a failed prefix cannot be frozen afterward");
            assertFalse(observation.sawOutcome);
            assertTrue(observation.workAfterFailure > 0);
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void reachingTheResultCountWithoutOverflowKeepsBothAlternatives() {
        var observation = new Observation();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = alternatives().match(new VariableExpr("x"),new ExprMatcher.MatchOptions(null,2,100,100));
            assertEquals(List.of(List.of("any"),List.of("literal-variable")),result.matches().stream()
                .map(ExprMatcher.MatchResult::trace).toList());
            assertTrue(result.complete()); assertTrue(result.diagnostics().isEmpty());
        }
        assertFalse(observation.sawPrefix);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
}
