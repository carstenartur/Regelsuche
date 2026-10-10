package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NativeConcurrentSessionsTest {
    private static final SearchExpressionStore.Limits ADEQUATE =
        new SearchExpressionStore.Limits(1_000, 1_000_000, 1_000_000, 64);
    private static final SearchExpressionStore.Limits EXHAUSTED =
        new SearchExpressionStore.Limits(4, 1_000_000, 1_000_000, 64);

    /** Contains no executor, barrier, captured callback or other thread's state. */
    private static final class ParentObservation implements RetainedOperation.Sink {
        private final long ownerThread = Thread.currentThread().threadId();
        private final char[] marker;
        private RetainedOperation scope;
        private long execution, validation, checkpoints;

        private ParentObservation(int markerLength) { marker = new char[markerLength]; }
        private void requireOwner() { assertEquals(ownerThread, Thread.currentThread().threadId()); }
        @Override public void executionWork(long units) {
            requireOwner(); execution = Math.addExact(execution, units);
        }
        @Override public void validationWork(long units) {
            requireOwner(); validation = Math.addExact(validation, units);
        }
        @Override public long observedWork() { requireOwner(); return execution + validation; }
        @Override public void checkpoint() {
            requireOwner(); checkpoints++;
            assertEquals(marker.length, RetainedGraph.measure(scope).retained().characters(),
                "only this thread's actual parent marker belongs to its restored scope");
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(scope); visitor.reference(marker);
        }
    }

    private record Run(NativeMoveSearch.Result result, ParentObservation parent,
            RetainedOperation.Frame markerFrame, long workerThread) { }

    private static Run run(NativeMoveSearch engine, NativeMoveSearch.Problem problem,
            SearchExpressionStore.Limits limits, int markerLength, CyclicBarrier coordination) throws Exception {
        var parent = new ParentObservation(markerLength);
        NativeMoveSearch.Result result;
        RetainedOperation.Frame markerFrame;
        try (var scope = RetainedOperation.open(parent)) {
            parent.scope = scope;
            markerFrame = RetainedOperation.retain((Object) parent.marker);
            try (markerFrame) {
                long before = parent.observedWork();
                // Both parent scopes coexist before either native run starts and until both finish.
                // Coordination is external test infrastructure, never a native problem callback.
                if (coordination != null) coordination.await(10, TimeUnit.SECONDS);
                result = engine.search(problem, SearchContinuationContract.PATH_SENSITIVE, limits);
                if (coordination != null) coordination.await(10, TimeUnit.SECONDS);
                assertEquals(before, parent.observedWork(), "native work must not charge the caller's scope");
                assertEquals(before, RetainedOperation.observedWork(), "native close restores this thread's parent");
                RetainedOperation.work(17);
                RetainedOperation.validation(19);
                RetainedOperation.checkpoint();
                assertEquals(before + 36, parent.observedWork());
            }
        }
        long closedWork = parent.execution + parent.validation;
        RetainedOperation.work(101);
        RetainedOperation.validation(103);
        assertEquals(closedWork, parent.execution + parent.validation, "closed scopes retain no active thread binding");
        assertEquals(0, RetainedOperation.observedWork());
        assertEquals(new RetainedGraph.Usage(0, 0, 4), RetainedGraph.measure(parent.scope).retained());
        assertEquals(new RetainedGraph.Usage(0, 0, 4), RetainedGraph.measure(markerFrame).retained());
        return new Run(result, parent, markerFrame, Thread.currentThread().threadId());
    }

    @ParameterizedTest
    @EnumSource(value = MoveSearch.Scheduling.class, names = {"STAGED", "STAGED_INCREMENTAL"})
    void concurrentSuccessAndRetentionAbortMatchTheirIndependentReferences(MoveSearch.Scheduling scheduling)
            throws Exception {
        var target = new VariableExpr("x");
        var zero = new NumberExpr(0);
        Expr source = new BinaryExpr(new BinaryExpr(target, BinaryOperator.ADD, zero), BinaryOperator.ADD, zero);
        var pattern = PatternExpr.var("A");
        var rule = new PatternRewriteRule("zero",
            PatternExpr.op(BinaryOperator.ADD, pattern, PatternExpr.num(0)), pattern);
        var descriptor = new MoveProvider.Descriptor("zero", "zero", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "concurrent-zero-v1");
        var provider = new NativeMoveSearch.Primitive(descriptor, new AstRewriteTransport(List.of(rule), 64, 128));
        var context = TypedMoveSearch.Context.frozen(target);
        var providers = List.<NativeMoveProvider>of(provider);
        var adequate = new NativeMoveSearch.Problem(source, context, providers, MoveSearch.Mode.FAST,
            scheduling, new MoveSearch.Budget(2, 2, 0, 20, 20_000_000));
        var exhausted = new NativeMoveSearch.Problem(source, context, providers, MoveSearch.Mode.FAST,
            scheduling, new MoveSearch.Budget(2, 2, 0, 20, 3_000_000));
        assertEquals(EXHAUSTED.nodes(), RetainedGraph.measure(exhausted).retained().nodes(),
            "the constrained session admits every shared input node before additional search allocations");
        var engine = new NativeMoveSearch();
        var adequateReference = run(engine, adequate, ADEQUATE, 17, null);
        var exhaustedReference = run(engine, exhausted, EXHAUSTED, 41, null);

        var phases = new AtomicInteger();
        var coordination = new CyclicBarrier(2, phases::incrementAndGet);
        var workers = Executors.newFixedThreadPool(2);
        Run completed;
        Run aborted;
        try {
            var completion = workers.submit(() -> run(engine, adequate, ADEQUATE, 17, coordination));
            var abortion = workers.submit(() -> run(engine, exhausted, EXHAUSTED, 41, coordination));
            completed = completion.get(20, TimeUnit.SECONDS);
            aborted = abortion.get(20, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "test worker threads must terminate");
        }

        assertEquals(2, phases.get(), "both scopes crossed the start and completion barriers");
        assertNotEquals(completed.workerThread(), aborted.workerThread());
        assertNotEquals(Thread.currentThread().threadId(), completed.workerThread());
        assertNotEquals(Thread.currentThread().threadId(), aborted.workerThread());
        assertEquivalent(adequateReference, completed);
        assertEquivalent(exhaustedReference, aborted);
        assertEquals(MoveSearch.Outcome.TARGET_REACHED, completed.result().observedOutcome());
        assertSame(target, completed.result().output());
        assertEquals(2, completed.result().witness().size());
        assertTrue(completed.result().replayWork() > 0);
        assertTrue(completed.result().totalWork() <= adequate.budget().totalWork());
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE, aborted.result().observedOutcome());
        assertEquals("NATIVE_RETENTION_EXHAUSTED", aborted.result().accounting().detail());
        assertTrue(aborted.result().witness().isEmpty());
        assertSame(source, aborted.result().output());
        assertTrue(aborted.result().totalWork() > 0, "failed work remains in the aborted session's receipt");
        assertTrue(aborted.result().accounting().peak().nodes() > EXHAUSTED.nodes(),
            "the abort observes an actual additional allocation, beyond the admitted immutable inputs");
        assertNotSame(completed.result().accounting(), aborted.result().accounting());
        assertNoSessionReferences(completed, aborted);
        assertNoSessionReferences(aborted, completed);
    }

    private static void assertEquivalent(Run expected, Run actual) {
        var reference = expected.result();
        var result = actual.result();
        assertEquals(reference.observedOutcome(), result.observedOutcome());
        assertEquals(reference.workRevision(), result.workRevision());
        assertEquals(reference.workBudget(), result.workBudget());
        assertEquals(reference.totalWork(), result.totalWork());
        assertEquals(reference.metrics(), result.metrics());
        assertEquals(reference.replayWork(), result.replayWork());
        assertEquals(reference.witness(), result.witness());
        assertEquals(reference.cursorReceipts(), result.cursorReceipts());
        assertEquals(reference.batchCursorReceipts(), result.batchCursorReceipts());
        assertEquals(reference.observationsComplete(), result.observationsComplete());
        assertEquals(reference.accounting().detail(), result.accounting().detail());
        assertEquals(reference.accounting().validationWork(), result.accounting().validationWork());
        assertEquals(reference.accounting().executionWork(), result.accounting().executionWork());
        assertEquals(reference.accounting().storageWork(), result.accounting().storageWork());
        assertEquals(reference.accounting().retentionWork(), result.accounting().retentionWork());
        assertEquals(reference.accounting().peak(), result.accounting().peak());
        assertEquals(reference.accounting().resultRetained(), result.accounting().resultRetained());
        assertEquals(reference.accounting().externalRetained(), result.accounting().externalRetained());
        assertEquals(new RetainedGraph.Usage(0, 0, 0), result.accounting().live());
        assertEquals(RetainedGraph.measure(result).retained(), result.accounting().resultRetained());
        assertEquals(expected.parent().execution, actual.parent().execution);
        assertEquals(expected.parent().validation, actual.parent().validation);
        assertEquals(expected.parent().checkpoints, actual.parent().checkpoints);
        assertFalse(result.accountingComplete(), "concurrency coverage alone cannot qualify all P04 atomic work");
        assertFalse(result.withinBudget());
    }

    private static void assertNoSessionReferences(Run owner, Run other) {
        Set<Object> retained = reachable(owner.result());
        assertFalse(retained.contains(other.result()));
        assertFalse(retained.contains(other.parent()));
        assertFalse(retained.contains(other.parent().scope));
        assertFalse(retained.contains(other.markerFrame()));
        assertFalse(retained.stream().anyMatch(value -> value instanceof ParentObservation
            || value instanceof RetainedOperation || value instanceof RetainedOperation.Frame
            || value instanceof SearchExpressionStore || value instanceof NativeRetentionSession),
            "published results must not retain either session's mutable ownership graph");
    }

    /** Identity-only traversal outside every measured scope; shared immutable inputs are allowed. */
    private static Set<Object> reachable(Object root) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        var pending = new ArrayDeque<Object>();
        var visitor = new RetainedGraph.Visitor() {
            @Override public void reference(Object value) { if (value != null) pending.addLast(value); }
            @Override public void requireExact(Object value, Class<?> type) { assertEquals(type, value.getClass()); }
        };
        visitor.reference(root);
        while (!pending.isEmpty()) {
            Object value = pending.removeFirst();
            if (!seen.add(value)) continue;
            visitReferences(value, visitor);
        }
        return seen;
    }

    private static void visitReferences(Object value, RetainedGraph.Visitor visitor) {
        switch (value) {
            case RetainedGraph.View view -> view.retainedReferences(visitor);
            case BinaryExpr binary -> {
                visitor.reference(binary.left()); visitor.reference(binary.right());
            }
            case FunctionExpr function -> visitor.reference(function.arguments());
            case Collection<?> collection -> collection.forEach(visitor::reference);
            case Map<?, ?> map -> map.forEach((key, entry) -> {
                visitor.reference(key); visitor.reference(entry);
            });
            case Object[] array -> {
                for (Object entry : array) visitor.reference(entry);
            }
            case Optional<?> optional -> visitor.reference(optional.orElse(null));
            default -> { }
        }
    }
}
