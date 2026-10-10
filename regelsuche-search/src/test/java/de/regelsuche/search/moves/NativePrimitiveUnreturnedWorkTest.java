package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import de.regelsuche.transform.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativePrimitiveUnreturnedWorkTest {
    /** Abort real structural comparison of the proposed and freshly regenerated targets. */
    private static final class ComparisonLimit implements RetainedOperation.Sink {
        final Expr proposed;
        final SearchExecution.ResourceLimit primary = new SearchExecution.ResourceLimit();
        RetainedOperation scope;
        long work;
        boolean failed;
        ComparisonLimit(Expr proposed) { this.proposed = proposed; }
        @Override public void executionWork(long units) { work = Math.addExact(work, units); }
        @Override public void validationWork(long units) { work = Math.addExact(work, units); }
        @Override public long observedWork() { return work; }
        @Override public void retainedReferences(RetainedGraph.Visitor v) { v.reference(scope); v.reference(proposed); }
        @Override public void checkpoint() {
            work = Math.addExact(work, RetainedGraph.measure(scope).work());
            var pending = new ArrayDeque<Object>();
            var seen = new IdentityHashMap<Object, Boolean>();
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value, Class<?> type) { assertEquals(type, value.getClass()); }
            };
            visitor.reference(scope);
            while (!pending.isEmpty()) {
                var value = pending.removeFirst();
                if (seen.put(value, Boolean.TRUE) != null) continue;
                if (!failed && value instanceof ExpressionIdentity identity) {
                    var operands = Collections.newSetFromMap(new IdentityHashMap<Expr, Boolean>());
                    identity.retainedReferences(new RetainedGraph.Visitor() {
                        @Override public void reference(Object item) { if (item instanceof Expr expression) operands.add(expression); }
                        @Override public void requireExact(Object item, Class<?> type) { assertEquals(type, item.getClass()); }
                    });
                    if (operands.size() == 2 && operands.contains(proposed)) {
                        failed = true;
                        throw primary;
                    }
                }
                if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Collection<?> values) values.forEach(visitor::reference);
                else if (value instanceof Map<?, ?> values) values.forEach((key, item) -> { visitor.reference(key); visitor.reference(item); });
                else if (value instanceof Object[] values) for (var item : values) visitor.reference(item);
            }
        }
    }
    private static final class InterruptedVerifier implements NativeVerifier, RetainedGraph.View {
        final NativeMoveSearch.Primitive delegate;
        final int stopAt;
        int calls;
        ComparisonLimit observer;
        InterruptedVerifier(NativeMoveSearch.Primitive delegate, int stopAt) { this.delegate = delegate; this.stopAt = stopAt; }
        @Override public NativeVerification verify(TypedMoveSearch.State state, NativeSearchMove move, TypedMoveSearch.Context context) {
            if (++calls != stopAt) return delegate.verify(state, move, context);
            observer = new ComparisonLimit(move.targetExpression());
            try (var scope = RetainedOperation.open(observer)) {
                observer.scope = scope;
                return delegate.verify(state, move, context);
            } finally { RetainedOperation.work(observer.work); }
        }
        @Override public void retainedReferences(RetainedGraph.Visitor v) { v.reference(delegate); }
    }
    private static NativeMoveSearch.Primitive provider() {
        var a = PatternExpr.var("A");
        var zero = new PatternRewriteRule("zero", PatternExpr.op(BinaryOperator.ADD, a, PatternExpr.num(0)), a);
        return new NativeMoveSearch.Primitive(new MoveProvider.Descriptor("zero", "zero", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "zero"),
            new AstRewriteTransport(List.of(zero), 64, 128));
    }
    private static NativeMoveSearch.Problem problem(NativeMoveSearch.Primitive provider, NativeVerifier verifier) {
        var x = new VariableExpr("x"); var y = new VariableExpr("y");
        var source = new BinaryExpr(new BinaryExpr(x, BinaryOperator.ADD, new NumberExpr(0)), BinaryOperator.MUL, y);
        var target = new BinaryExpr(x, BinaryOperator.MUL, y);
        return new NativeMoveSearch.Problem(source, TypedMoveSearch.Context.frozen(target), List.of(provider), MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(1, 1, 0, 10, 100_000_000),
            NativeMovePriorityPolicy.INVENTORY_ORDER, NativeMoveSearch.ZeroScore.INSTANCE, NativeStateValue.NONE, verifier);
    }
    private static long regeneration(NativeMoveSearch.Primitive provider, NativeMoveSearch.Problem problem) {
        int count = provider.transport().generate(problem.source()).size();
        assertEquals(1, count);
        return TransformationWorkMetrics.flatEngine(count).withCandidateWork(new ExecutionWork(count, 0, 0)).totalWorkUnitsV2();
    }
    @Test void comparisonFailureTransfersCompletedPrimitiveRegenerationExactlyOnce() {
        var provider = provider(); var verifier = new InterruptedVerifier(provider, 1); var problem = problem(provider, verifier);
        var state = new TypedMoveSearch.State(problem.source(), 0, 0, "", List.of(), Set.of(), 0);
        var move = provider.candidates(state, problem.context()).moves().getFirst();
        var failure = assertThrows(SearchExecution.ResourceLimit.class, () -> verifier.verify(state, move, problem.context()));
        assertSame(verifier.observer.primary, failure);
        assertTrue(verifier.observer.failed);
        var paid = failure.takeWork();
        assertEquals(regeneration(provider, problem), paid.verification());
        assertEquals(paid.verification(), paid.total());
        assertEquals(0, failure.takeWork().total());
    }
    private static void verificationFailure(int stopAt) {
        var provider = provider(); var verifier = new InterruptedVerifier(provider, stopAt); var problem = problem(provider, verifier);
        long regeneration = regeneration(provider, problem);
        var result = NativeMoveSearch.boundedAccounting().search(problem, SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(stopAt, verifier.calls);
        assertTrue(verifier.observer.failed);
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE, result.observedOutcome());
        assertFalse(result.accountingComplete());
        assertEquals(regeneration, result.metrics().verificationWork());
        assertEquals(stopAt == 1 ? 0 : regeneration, result.replayWork());
        assertEquals(0, verifier.observer.primary.takeWork().total(), "search consumes the transferred receipt exactly once");
    }
    @Test void admissionKeepsCompletedPrimitiveRegenerationWhenComparisonAborts() { verificationFailure(1); }
    @Test void finalReplayKeepsCompletedPrimitiveRegenerationWhenComparisonAborts() { verificationFailure(2); }
}
