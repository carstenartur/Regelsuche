package de.regelsuche.moves.enumerate;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class TreePositionOwnershipTest {
    private static final class Limit extends RuntimeException {}

    /** Observes the public ownership graph, without reflecting into implementation fields. */
    private static final class Probe implements RetainedOperation.Sink {
        RetainedOperation scope;
        long execution;
        final Limit primary = new Limit();
        final Set<RetainedOperation.Frame> frames = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Set<Object>> checkpoints = new ArrayList<>();
        Predicate<Set<Object>> failDebit, failCheckpoint;
        Set<Object> failedDebit;
        boolean failed, repeatOnClose;
        RuntimeException cleanup;

        @Override public void executionWork(long units) {
            execution += units;
            if (failed && units == 4 && repeatOnClose) {
                repeatOnClose = false;
                throw cleanup == null ? primary : cleanup;
            }
            if (!failed && failDebit != null) {
                var live = graph(scope);
                if (failDebit.test(live)) {
                    failed = true;
                    failedDebit = live;
                    throw primary;
                }
            }
        }
        @Override public void validationWork(long units) { fail("navigation must not duplicate mathematical validation"); }
        @Override public void retainedReferences(RetainedGraph.Visitor v) { v.reference(scope); }
        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var live = graph(scope);
            checkpoints.add(live);
            for (var value : live) if (value instanceof RetainedOperation.Frame frame) frames.add(frame);
            if (!failed && failCheckpoint != null && failCheckpoint.test(live)) {
                failed = true;
                throw primary;
            }
        }
        void assertReleased() {
            assertEquals(0, RetainedGraph.measure(scope).retained().nodes());
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
            visitReferences(value, visitor);
        }
        return seen;
    }

    private static void visitReferences(Object value, RetainedGraph.Visitor visitor) {
        switch (value) {
            case RetainedGraph.View view -> view.retainedReferences(visitor);
            case BinaryExpr binary -> { visitor.reference(binary.left()); visitor.reference(binary.right()); }
            case FunctionExpr function -> visitor.reference(function.arguments());
            case Collection<?> collection -> collection.forEach(visitor::reference);
            case Optional<?> optional -> optional.ifPresent(visitor::reference);
            case Object[] array -> {
                for (var item : array) visitor.reference(item);
            }
            default -> { }
        }
    }

    @Test void nestedReplacementRetainsActualAncestorsAndBothFunctionArgumentLists() {
        var selected = new VariableExpr("selected");
        var shared = new VariableExpr("shared");
        var replacement = new VariableExpr("replacement");
        var child = new BinaryExpr(selected, BinaryOperator.ADD, shared);
        var function = new FunctionExpr("f", List.of(shared, child));
        var root = new BinaryExpr(function, BinaryOperator.MUL, shared);
        var position = new TreePosition(List.of(0, 1, 0), "display only");
        var probe = new Probe();
        TreePosition.ReplacementResult result;
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            result = position.replaceAt(root, replacement);
        }
        assertTrue(probe.checkpoints.stream().anyMatch(live -> live.contains(root) && live.contains(replacement)
            && live.contains(result)), "the actual typed result must overlap its source and replacement");
        assertTrue(probe.checkpoints.stream().anyMatch(live -> live.stream().anyMatch(value ->
            value instanceof FunctionExpr rebuilt && rebuilt != function && live.stream().anyMatch(other ->
                other instanceof ArrayList<?> copy && copy != rebuilt.arguments() && sameReferences(copy, rebuilt.arguments())))),
            "the mutable argument copy must overlap FunctionExpr's immutable copy");
        assertTrue(probe.checkpoints.stream().anyMatch(live -> live.stream().anyMatch(value ->
            value instanceof ArrayList<?> parents && parents.size() == position.path().size()
                && parents.stream().allMatch(RetainedGraph.View.class::isInstance)
                && live.stream().anyMatch(other -> other instanceof List<?> frozen && frozen != parents
                    && sameReferences(frozen, parents)))), "completed navigation retains the mutable and immutable parent chains");
        var rewritten = assertInstanceOf(BinaryExpr.class, result.rewrittenRoot().orElseThrow());
        var rebuiltFunction = assertInstanceOf(FunctionExpr.class, rewritten.left());
        var rebuiltChild = assertInstanceOf(BinaryExpr.class, rebuiltFunction.arguments().get(1));
        assertAll(() -> assertEquals(3, result.copiedAncestors()),
            () -> assertSame(selected, result.selectedSubtree().orElseThrow()),
            () -> assertSame(shared, rewritten.right()),
            () -> assertSame(shared, rebuiltFunction.arguments().get(0)),
            () -> assertSame(shared, rebuiltChild.right()),
            () -> assertSame(replacement, rebuiltChild.left()));
        probe.assertReleased();
    }

    @Test void rootReplacementPublishesTheSameSubtreeWithoutCopyingIt() {
        var source = new VariableExpr("source");
        var replacement = new VariableExpr("replacement");
        var probe = new Probe();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            var result = new TreePosition(List.of(), "").replaceAt(source, replacement);
            assertEquals(0, result.copiedAncestors());
            assertSame(replacement, result.rewrittenRoot().orElseThrow());
            assertTrue(probe.checkpoints.stream().anyMatch(live -> live.contains(result)), "root replacement also owns its result");
        }
        probe.assertReleased();
    }

    @Test void selectedInvalidAndAbsentOutcomesRemainMeasuredAndDistinct() {
        var leaf = new VariableExpr("leaf");
        for (var path : List.of(List.<Integer>of(), List.of(-1), List.of(0))) {
            var probe = new Probe();
            try (var scope = RetainedOperation.open(probe)) {
                probe.scope = scope;
                var result = new TreePosition(path, "").selectAt(leaf);
                var expected = path.isEmpty() ? TreePosition.Status.SELECTED
                    : path.getFirst() < 0 ? TreePosition.Status.INVALID_PATH : TreePosition.Status.POSITION_NOT_PRESENT;
                assertEquals(expected, result.status());
                assertTrue(probe.checkpoints.stream().anyMatch(live -> live.contains(result)), "typed selection outcome is retained");
            }
            probe.assertReleased();
        }
    }

    private static boolean copiedArguments(Set<Object> live, List<Expr> originals) {
        return live.stream().anyMatch(value -> value instanceof ArrayList<?> copy && sameReferences(copy, originals));
    }

    /** Both copies must contain the same actual objects, without entering paid Expr equality. */
    private static boolean sameReferences(List<?> first, List<?> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) if (first.get(i) != second.get(i)) return false;
        return true;
    }

    @Test void failedArgumentAllocationDebitStillObservesTheActualUnmodifiedCopy() {
        var arguments = List.<Expr>of(new VariableExpr("a"), new VariableExpr("b"), new VariableExpr("c"));
        var root = new FunctionExpr("f", arguments);
        var probe = new Probe();
        probe.failDebit = live -> copiedArguments(live, arguments);
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertSame(probe.primary, assertThrows(Limit.class, () -> new TreePosition(List.of(1), "")
                .replaceAt(root, new VariableExpr("replacement"))));
            assertTrue(copiedArguments(probe.failedDebit, arguments));
            assertTrue(probe.checkpoints.stream().anyMatch(live -> copiedArguments(live, arguments)),
                "an allocation debit failure cannot skip observation of the already copied list");
        }
        probe.assertReleased();
    }

    @Test void failedRebuiltNodeDebitStillObservesTheActualNewAncestor() {
        var root = new BinaryExpr(new VariableExpr("a"), BinaryOperator.ADD, new VariableExpr("b"));
        var probe = new Probe();
        Predicate<Set<Object>> rebuilt = live -> live.stream().anyMatch(value -> value instanceof BinaryExpr && value != root);
        probe.failDebit = rebuilt;
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertSame(probe.primary, assertThrows(Limit.class, () -> new TreePosition(List.of(0), "")
                .replaceAt(root, new VariableExpr("replacement"))));
            assertTrue(probe.checkpoints.stream().anyMatch(rebuilt), "the completed ancestor survives its failed debit checkpoint");
        }
        probe.assertReleased();
    }

    @Test void failedNavigationAllocationDebitStillObservesItsEmptyParentList() {
        var probe = new Probe();
        Predicate<Set<Object>> parents = live -> live.stream().anyMatch(value -> value instanceof ArrayList<?> list && list.isEmpty());
        probe.failDebit = parents;
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertSame(probe.primary, assertThrows(Limit.class,
                () -> new TreePosition(List.of(), "").selectAt(new VariableExpr("a"))));
            assertTrue(probe.checkpoints.stream().anyMatch(parents), "the empty navigation buffer exists even if its allocation debit fails");
        }
        probe.assertReleased();
    }

    @Test void failedFrozenNavigationDebitStillObservesBothParentLists() {
        var probe = new Probe();
        Predicate<Set<Object>> bothParents = live -> live.stream().anyMatch(value -> value instanceof ArrayList<?> parents
            && parents.size() == 1 && parents.getFirst() instanceof RetainedGraph.View
            && live.stream().anyMatch(other -> other instanceof List<?> frozen && frozen != parents && sameReferences(frozen, parents)));
        probe.failDebit = bothParents;
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertSame(probe.primary, assertThrows(Limit.class, () -> new TreePosition(List.of(0), "")
                .selectAt(new FunctionExpr("f", new VariableExpr("a")))));
            assertTrue(probe.checkpoints.stream().anyMatch(bothParents), "completed parent copying is observed before release on debit failure");
        }
        probe.assertReleased();
    }

    @Test void widerFunctionRebuildPaysBothActualArgumentCopies() {
        long small = rebuildWork(2), large = rebuildWork(20);
        assertTrue(large - small >= 2L * (20 - 2), "each additional argument is copied into both mutable and immutable lists");
    }

    private static long rebuildWork(int width) {
        var arguments = new ArrayList<Expr>();
        for (int i = 0; i < width; i++) arguments.add(new VariableExpr("a" + i));
        var probe = new Probe();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertTrue(new TreePosition(List.of(0), "").replaceAt(new FunctionExpr("f", arguments), new VariableExpr("replacement")).success());
        }
        probe.assertReleased();
        return probe.execution;
    }

    @Test void resultFailureSurvivesRepeatedOrDistinctReleaseFailure() {
        for (boolean distinct : List.of(false, true)) {
            var probe = new Probe();
            probe.failCheckpoint = live -> live.stream().anyMatch(TreePosition.ReplacementResult.class::isInstance);
            probe.repeatOnClose = true;
            if (distinct) probe.cleanup = new IllegalStateException("release limit");
            try (var scope = RetainedOperation.open(probe)) {
                probe.scope = scope;
                assertSame(probe.primary, assertThrows(Limit.class, () -> new TreePosition(List.of(0), "")
                    .replaceAt(new FunctionExpr("f", new VariableExpr("a")), new VariableExpr("replacement"))));
                assertEquals(distinct ? List.of(probe.cleanup) : List.of(), List.of(probe.primary.getSuppressed()));
            }
            probe.assertReleased();
        }
    }
}
