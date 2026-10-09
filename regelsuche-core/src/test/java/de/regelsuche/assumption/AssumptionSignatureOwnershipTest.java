package de.regelsuche.assumption;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class AssumptionSignatureOwnershipTest {
    private static final class Limit extends IllegalArgumentException {}

    private static final class Probe implements RetainedOperation.Sink {
        RetainedOperation scope;
        long work;
        final Limit primary = new Limit();
        final List<Set<Object>> observations = new ArrayList<>();
        final Set<RetainedOperation.Frame> frames = Collections.newSetFromMap(new IdentityHashMap<>());
        Predicate<Set<Object>> failDebit, failObservation;
        boolean failed, repeatOnClose;
        RuntimeException cleanup;

        @Override public void executionWork(long units) {
            work += units;
            if (failed && units == 4 && repeatOnClose) {
                repeatOnClose = false;
                throw cleanup == null ? primary : cleanup;
            }
            if (!failed && failDebit != null && failDebit.test(graph(scope))) {
                failed = true;
                throw primary;
            }
        }
        @Override public void validationWork(long units) { fail("metadata must not repeat mathematical validation"); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }
        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var live = graph(scope);
            observations.add(live);
            for (var value : live) if (value instanceof RetainedOperation.Frame frame) frames.add(frame);
            if (!failed && failObservation != null && failObservation.test(live)) {
                failed = true;
                throw primary;
            }
        }
        void assertReleased() {
            assertEquals(0, RetainedGraph.measure(scope).retained().characters());
            for (var frame : frames) assertEquals(new RetainedGraph.Usage(0, 0, 4), RetainedGraph.measure(frame).retained());
        }
    }

    private static Set<Object> graph(Object root) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        var pending = new ArrayDeque<Object>();
        var visitor = new RetainedGraph.Visitor() {
            @Override public void reference(Object value) { if (value != null) pending.addLast(value); }
            @Override public void requireExact(Object value, Class<?> type) { assertEquals(type, value.getClass()); }
        };
        visitor.reference(root);
        while (!pending.isEmpty()) {
            var value = pending.removeFirst();
            if (!seen.add(value)) continue;
            if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
            else if (value instanceof Collection<?> collection) collection.forEach(visitor::reference);
            else if (value instanceof Object[] array) for (var item : array) visitor.reference(item);
        }
        return seen;
    }

    private static boolean text(Set<Object> live, String expected) {
        return live.stream().anyMatch(value -> value instanceof String string && string.equals(expected));
    }

    private static boolean frozenSet(Set<Object> live) {
        return live.stream().anyMatch(value -> value instanceof TreeSet<?> set && !set.isEmpty()
            && live.stream().anyMatch(other -> other instanceof List<?> list && list.equals(new ArrayList<>(set))));
    }

    @Test void normalizationRetainsIntermediateStringsAndTheActualSetListFingerprintAndResult() {
        var input = new ArrayList<>(List.of("  ((x))≠0   ", "0 != (y)", "not(z=0)", " ", "x != 0"));
        input.add(null);
        var probe = new Probe();
        AssumptionSignature result;
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            result = AssumptionSignature.ofExpressions(input);
            assertEquals(List.of("x != 0", "y != 0", "z != 0"), result.normalizedAssumptions());
            assertEquals("x != 0;y != 0;z != 0", result.fingerprint());
            for (String intermediate : List.of("((x))≠0", "((x))!=0", "((x)) != 0", "(x)", "x")) {
                assertTrue(probe.observations.stream().anyMatch(live -> text(live, intermediate)), intermediate);
            }
            assertTrue(probe.observations.stream().anyMatch(AssumptionSignatureOwnershipTest::frozenSet),
                "the actual mutable normalization set overlaps the frozen list");
            assertTrue(probe.observations.stream().anyMatch(live -> live.contains(result)
                && live.contains(result.normalizedAssumptions()) && live.contains(result.fingerprint())),
                "the completed metadata record owns its actual list and fingerprint");
            assertTrue(probe.work > 1);
        }
        input.clear();
        assertEquals(3, result.normalizedAssumptions().size());
        assertThrows(UnsupportedOperationException.class, () -> result.normalizedAssumptions().clear());
        probe.assertReleased();
    }

    @Test void typedAssumptionsRetainNormalizedTextWhileAddingTheirKind() {
        var probe = new Probe();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            var result = AssumptionSignature.ofAssumptions(List.of(Assumption.nonZero(" ((q)) "), Assumption.positive("q")));
            assertEquals(List.of("NON_ZERO|q != 0", "POSITIVE|q > 0"), result.normalizedAssumptions());
            assertTrue(probe.observations.stream().anyMatch(live -> text(live, "q != 0") && text(live, "NON_ZERO|q != 0")),
                "the normalized expression and newly prefixed string overlap");
        }
        probe.assertReleased();
    }

    @Test void mergingRetainsBothSourceSignaturesAndTheNewCollectionCopy() {
        var left = AssumptionSignature.ofExpressions(List.of("b != 0", "a > 0"));
        var right = AssumptionSignature.ofExpressions(List.of("c < 0", "b != 0"));
        var probe = new Probe();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            var result = AssumptionSignature.merge(left, right);
            assertEquals("a > 0;b != 0;c < 0", result.fingerprint());
            assertTrue(probe.observations.stream().anyMatch(live -> live.contains(left) && live.contains(right)
                && live.contains(result) && frozenSet(live)));
        }
        probe.assertReleased();
    }

    @Test void directConstructionObservesTheDefensiveCopyBeforeItsAllocationDebitCanFail() {
        var input = new ArrayList<>(List.of("x != 0"));
        var probe = new Probe();
        Predicate<Set<Object>> copied = live -> live.contains(input) && live.stream().anyMatch(value ->
            value instanceof AssumptionSignature signature && signature.normalizedAssumptions() != input
                && signature.normalizedAssumptions().equals(input));
        probe.failDebit = copied;
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertSame(probe.primary, assertThrows(Limit.class, () -> new AssumptionSignature(input, "x != 0")));
            assertTrue(probe.observations.stream().anyMatch(copied));
        }
        probe.assertReleased();
    }

    @Test void failedNormalizationDebitStillObservesBothOldAndNewStrings() {
        var probe = new Probe();
        Predicate<Set<Object>> replaced = live -> text(live, "x≠0") && text(live, "x!=0");
        probe.failDebit = replaced;
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertSame(probe.primary, assertThrows(Limit.class, () -> AssumptionSignature.normalizeExpression("x≠0")));
            assertTrue(probe.observations.stream().anyMatch(replaced));
        }
        probe.assertReleased();
    }

    @Test void failureAtTheFrozenListOrFingerprintRetainsAlreadyCompletedMetadata() {
        for (var stage : List.<Predicate<Set<Object>>>of(AssumptionSignatureOwnershipTest::frozenSet,
                live -> text(live, "a != 0;b != 0"))) {
            var probe = new Probe();
            probe.failDebit = stage;
            try (var scope = RetainedOperation.open(probe)) {
                probe.scope = scope;
                assertSame(probe.primary, assertThrows(Limit.class,
                    () -> AssumptionSignature.ofExpressions(List.of("b≠0", "a≠0"))));
                assertTrue(probe.observations.stream().anyMatch(stage));
            }
            probe.assertReleased();
        }
    }

    @Test void observationFailureKeepsThePrimaryThrowableThroughIdenticalOrDistinctCleanupFailure() {
        for (boolean distinct : List.of(false, true)) {
            var probe = new Probe();
            probe.failObservation = live -> text(live, "x!=0");
            probe.repeatOnClose = true;
            if (distinct) probe.cleanup = new IllegalStateException("metadata cleanup failed");
            try (var scope = RetainedOperation.open(probe)) {
                probe.scope = scope;
                assertSame(probe.primary, assertThrows(Limit.class, () -> AssumptionSignature.ofExpressions(List.of("x≠0"))));
                assertEquals(distinct ? List.of(probe.cleanup) : List.of(), List.of(probe.primary.getSuppressed()));
            }
            probe.assertReleased();
        }
    }
}
