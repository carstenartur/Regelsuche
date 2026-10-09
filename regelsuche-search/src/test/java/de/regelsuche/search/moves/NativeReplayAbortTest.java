package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.RewriteKind;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Calls the real private replay boundary, with deterministic failures in its paid Expr comparisons. */
class NativeReplayAbortTest {
    private enum Point { NONE, ROOT, LINEAGE, RECEIPT, ENDPOINT, VERIFY }
    private static final class Probe implements RetainedOperation.Sink {
        RetainedOperation scope;
        NativeRetentionSession accounting;
        Point point = Point.NONE;
        int afterChecks, checks, witnessSize;
        long work;
        boolean failed, receiptOwnedAtComparison;
        NativeVerification lastReturned; // observer diagnostics, never a production owner
        final SearchExecution.ResourceLimit limit = new SearchExecution.ResourceLimit();
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); visitor.reference(accounting); }
        @Override public void executionWork(long units) { work += units; }
        @Override public void validationWork(long units) { work += units; }
        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var stack = StackWalker.getInstance().walk(frames -> frames.map(frame -> frame.getClassName() + "#" + frame.getMethodName()).toList());
            if (stack.stream().noneMatch(name -> name.equals(NativeMoveSearch.class.getName() + "#replay"))) return;
            boolean verifying = stack.stream().anyMatch(name -> name.endsWith("$Execution#verify"));
            boolean comparingReceipt = stack.contains(NativeVerification.class.getName() + "#equals");
            if (comparingReceipt && lastReturned != null) {
                var live = graph(scope);
                var proof = (NativeMoveProof.Primitive) lastReturned.checkedProof();
                receiptOwnedAtComparison |= live.contains(lastReturned) && live.contains(proof) && live.contains(proof.step());
            }
            Point here = Point.NONE;
            if (!verifying && comparingReceipt) here = Point.RECEIPT;
            else if (!verifying && stack.contains(TypedMoveSearch.State.class.getName() + "#equals")) here = Point.LINEAGE;
            else if (!verifying && stack.contains("de.regelsuche.ast.ExpressionIdentity#same")) here = checks == witnessSize ? Point.ENDPOINT : Point.ROOT;
            if (failed || (point != Point.NONE && point == here && checks == afterChecks)) {
                failed = true;
                throw limit;
            }
        }
    }
    private static Set<Object> graph(Object root) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        var pending = new ArrayDeque<Object>();
        var visitor = new RetainedGraph.Visitor() {
            @Override public void reference(Object value) { if (value != null) pending.addLast(value); }
            @Override public void requireExact(Object value,Class<?> type) { assertEquals(type, value.getClass()); }
        };
        visitor.reference(root);
        while (!pending.isEmpty()) {
            var value = pending.removeFirst();
            if (!seen.add(value)) continue;
            if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
            else if (value instanceof Map<?,?> map) map.forEach((key, entry) -> { visitor.reference(key); visitor.reference(entry); });
            else if (value instanceof Collection<?> collection) collection.forEach(visitor::reference);
            else if (value instanceof Object[] array) for (var item : array) visitor.reference(item);
        }
        return seen;
    }
    private record Checker(Probe probe) implements NativeVerifier,RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(probe); }
        @Override public NativeVerification verify(TypedMoveSearch.State state,NativeSearchMove move,TypedMoveSearch.Context context) {
            ++probe.checks;
            if (probe.point == Point.VERIFY && probe.checks == probe.afterChecks) {
                probe.failed = true;
                throw probe.limit.paidVerification(13);
            }
            var step = ((NativeMoveProof.Primitive) move.proof()).step();
            var fresh = receipt(variableName(step.source()), variableName(step.target()));
            probe.lastReturned = fresh;
            return fresh;
        }
    }
    private static String variableName(Expr expression) { return ((VariableExpr) expression).name(); }
    private static NativeVerification receipt(String source,String target) {
        var step = new AstRewriteTransport.Step(new VariableExpr(source), new VariableExpr(target), "fixture",
            RewriteKind.SIMPLIFY, false, 0, true, List.of(), "fixture", "MIT");
        return new NativeVerification(true, 37, new NativeMoveProof.Primitive(step), "fixture", "checked");
    }
    private static TypedMoveSearch.State state(String expression,int depth) {
        return new TypedMoveSearch.State(new VariableExpr(expression), depth, depth, depth == 0 ? "" : "fixture", List.of(), Set.of(), 0);
    }
    private static SearchExecution.Step<TypedMoveSearch.State,NativeSearchMove,NativeVerification> step(String source,String target,int depth) {
        var verified = receipt(source, target);
        var descriptor = new MoveProvider.Descriptor("fixture", "fixture", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.VERIFIED, List.of(), SearchMove.ValueEvidence.UNKNOWN, "fixture-v1");
        var move = new NativeSearchMove(verified.checkedProof(), descriptor, 0, Set.of());
        return new SearchExecution.Step<>(state(source, depth), state(target, depth + 1), move, verified);
    }
    private record Witness(Expr source,Expr target,List<SearchExecution.Step<TypedMoveSearch.State,NativeSearchMove,NativeVerification>> steps) {}
    private static Witness witness(int size) {
        var steps = new ArrayList<SearchExecution.Step<TypedMoveSearch.State,NativeSearchMove,NativeVerification>>();
        for (int i = 0; i < size; i++) steps.add(step("x" + i, "x" + (i + 1), i));
        return new Witness(new VariableExpr("x0"), new VariableExpr("x" + size), List.copyOf(steps));
    }
    private record Returned(long work,NativeVerification rejected) {}
    private static Returned invokeReplay(Object execution,Witness witness) {
        try {
            var method = NativeMoveSearch.class.getDeclaredMethod("replay", execution.getClass(), Expr.class, Expr.class, List.class);
            method.setAccessible(true);
            var returned = method.invoke(null, execution, witness.source(), witness.target(), witness.steps());
            var work = returned.getClass().getDeclaredMethod("work"); work.setAccessible(true);
            var rejected = returned.getClass().getDeclaredMethod("rejected"); rejected.setAccessible(true);
            return new Returned((long) work.invoke(returned), (NativeVerification) rejected.invoke(returned));
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static Returned run(Probe probe,Witness witness,boolean accounted) {
        probe.witnessSize = witness.steps().size();
        var problem = new NativeMoveSearch.Problem(witness.source(), TypedMoveSearch.Context.frozen(witness.target()), List.of(),
            MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(3, 3, 0, 10, 10_000_000),
            NativeMovePriorityPolicy.INVENTORY_ORDER, NativeMoveSearch.ZeroScore.INSTANCE, NativeStateValue.NONE, new Checker(probe));
        try (var store = new SearchExpressionStore(SearchExpressionStore.Limits.DEFAULT)) {
            probe.accounting = new NativeRetentionSession(problem, store, SearchExpressionStore.Limits.DEFAULT);
            Class<?> executionType;
            Object execution;
            try {
                executionType = Class.forName(NativeMoveSearch.class.getName() + "$Execution");
                var constructor = executionType.getDeclaredConstructor(NativeMoveSearch.Problem.class, SearchExpressionStore.class, NativeRetentionSession.class);
                constructor.setAccessible(true);
                execution = constructor.newInstance(problem, store, accounted ? probe.accounting : null);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            try (var scope = RetainedOperation.open(probe)) {
                probe.scope = scope;
                probe.accounting.operation(scope);
                return invokeReplay(execution, witness);
            }
        }
    }
    private static void abort(Point point,int checks,int size,long expectedWork) {
        var probe = new Probe(); probe.point = point; probe.afterChecks = checks;
        var result = assertDoesNotThrow(() -> run(probe, witness(size), true));
        assertTrue(probe.failed, "the chosen paid comparison must actually have run");
        assertFalse(probe.accounting.complete());
        assertEquals(expectedWork, result.work(), "completed verification plus unreturned failure work, each once");
        assertNull(result.rejected(), "a resource abort is not a mathematical rejection");
        assertEquals(0, probe.limit.takeWork().total());
        assertTrue(probe.work > 0);
        assertEquals(0, RetainedGraph.measure(probe.scope).retained().characters());
    }
    @Test void rootComparisonAbortReturnsAReceipt() { abort(Point.ROOT, 0, 1, 0); }
    @Test void lineageComparisonAbortPreservesTheVerifiedPrefix() { abort(Point.LINEAGE, 1, 2, 37); }
    @Test void firstReturnedReceiptComparisonAbortPreservesItsVerificationWork() { abort(Point.RECEIPT, 1, 1, 37); }
    @Test void secondReturnedReceiptComparisonAbortPreservesBothVerificationPayments() { abort(Point.RECEIPT, 2, 2, 74); }
    @Test void endpointComparisonAbortPreservesAllCompletedVerificationWork() { abort(Point.ENDPOINT, 2, 2, 74); }
    @Test void emptyWitnessEndpointComparisonAbortReturnsAnAccountedAttempt() { abort(Point.ENDPOINT, 0, 0, 0); }
    @Test void verificationAbortTransfersOnlyItsStillUnreturnedWork() { abort(Point.VERIFY, 2, 2, 50); }
    @Test void freshReturnedReceiptAndFullProducerStayOwnedDuringComparison() {
        var probe = new Probe();
        var result = run(probe, witness(2), true);
        assertEquals(74, result.work());
        assertNull(result.rejected());
        assertTrue(probe.receiptOwnedAtComparison, "the whole fresh checked receipt, producer and source binding must be live");
    }
    @Test void unaccountedReplayRethrowsTheSameResourceFailure() {
        var probe = new Probe(); probe.point = Point.RECEIPT; probe.afterChecks = 1;
        assertSame(probe.limit, assertThrows(SearchExecution.ResourceLimit.class, () -> run(probe, witness(1), false)));
    }
    @Test void semanticRootLineageAndEndpointMismatchesRemainErrors() {
        var original = witness(2);
        var foreignRoot = new Witness(new VariableExpr("foreign"), original.target(), original.steps());
        assertEquals("native replay root differs", assertThrows(IllegalStateException.class, () -> run(new Probe(), foreignRoot, true)).getMessage());
        var foreignLineage = new Witness(original.source(), original.target(), List.of(original.steps().getFirst(), step("foreign", "x2", 1)));
        assertEquals("broken native witness lineage", assertThrows(IllegalStateException.class, () -> run(new Probe(), foreignLineage, true)).getMessage());
        var foreignTarget = new Witness(original.source(), new VariableExpr("foreign"), original.steps());
        assertEquals("native replay endpoint differs", assertThrows(IllegalStateException.class, () -> run(new Probe(), foreignTarget, true)).getMessage());
        var empty = new Witness(new VariableExpr("same"), new VariableExpr("same"), List.of());
        assertEquals(0, run(new Probe(), empty, true).work());
    }
}
