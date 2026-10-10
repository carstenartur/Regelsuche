package de.regelsuche.search.moves;

import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import de.regelsuche.transform.*;
import java.util.List;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeExecutionInventoryTest {
    static NativeMoveSearch.Primitive provider(String id) {
        var a = PatternExpr.var("A");
        var rule = new PatternRewriteRule("zero", PatternExpr.op(BinaryOperator.ADD, a, PatternExpr.num(0)), a);
        var descriptor = new MoveProvider.Descriptor(id, "zero", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "inventory-test/v1");
        return new NativeMoveSearch.Primitive(descriptor, new AstRewriteTransport(List.of(rule), 32, 32));
    }
    private static NativeMoveSearch.Problem problem(List<NativeMoveProvider> providers, NativeVerifier verifier) {
        var x = new VariableExpr("x");
        return new NativeMoveSearch.Problem(x, TypedMoveSearch.Context.frozen(x), providers, MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(1, 1, 0, 8, 1_000_000),
            NativeMovePriorityPolicy.INVENTORY_ORDER, NativeMoveSearch.ZeroScore.INSTANCE, NativeStateValue.NONE, verifier);
    }
    @Test void registeredCheckerMustOwnTheActualProviderNotAnIdenticalDescriptor() {
        var p = provider("zero");
        var registered = NativeVerifier.registered(List.of(p));
        assertTrue(NativeExecutionInventory.eligible(problem(List.of(p), registered), null));
        assertFalse(NativeExecutionInventory.eligible(problem(List.of(provider("zero")), registered), null));
    }
    @Test void unknownEnumViewsAndCallerSuppliedCostFacetsCannotQualifyCallbacks() {
        var p = provider("zero");
        var known = problem(List.of(p), NativeVerifier.registered(List.of(p)));
        assertFalse(NativeExecutionInventory.eligible(copy(known, Unknown.INSTANCE, known.stateScore(), known.stateValue(), known.verifier()), null));
        assertFalse(NativeExecutionInventory.eligible(copy(known, known.policy(), Unknown.INSTANCE, known.stateValue(), known.verifier()), null));
        assertFalse(NativeExecutionInventory.eligible(copy(known, known.policy(), known.stateScore(), Unknown.INSTANCE, known.verifier()), null));
        assertFalse(NativeExecutionInventory.eligible(copy(known, known.policy(), known.stateScore(), known.stateValue(), Unknown.INSTANCE), null));
        assertFalse(NativeExecutionInventory.eligible(known, Unknown.INSTANCE));
    }
    @Test void unknownProviderIsRejectedWithoutInvokingItsDescriptorOrGenerator() {
        var p = provider("zero");
        NativeMoveProvider unknown = new NativeMoveProvider() {
            @Override public MoveProvider.Descriptor descriptor() { throw new AssertionError("unknown descriptor invoked"); }
            @Override public Batch candidates(TypedMoveSearch.State s, TypedMoveSearch.Context c) { throw new AssertionError("unknown generator invoked"); }
        };
        assertFalse(NativeExecutionInventory.eligible(problem(List.of(unknown), NativeVerifier.registered(List.of(p))), null));
    }
    @Test void rejectedAndSuccessfulInspectionBothHavePositivePaidWork() {
        var p = provider("zero");
        var verifier = NativeVerifier.registered(List.of(p));
        long accepted = work(problem(List.of(p), verifier));
        long rejected = work(problem(List.of(provider("foreign")), verifier));
        assertTrue(accepted > rejected);
        assertTrue(rejected > 0);
    }
    @Test void randomizedMapOrderDoesNotChangeThePaidInspection() {
        var first = provider("first");
        var second = provider("second");
        var both = NativeVerifier.registered(List.of(first, second));
        long expected = work(problem(List.of(first), NativeVerifier.registered(List.of(first)))) + 1;
        assertEquals(expected, work(problem(List.of(first), both)));
        assertEquals(expected, work(problem(List.of(second), both)));
    }
    private static NativeMoveSearch.Problem copy(NativeMoveSearch.Problem p, NativeMovePriorityPolicy policy,
            ToDoubleFunction<TypedMoveSearch.State> score, NativeStateValue value, NativeVerifier verifier) {
        return new NativeMoveSearch.Problem(p.source(), p.context(), p.providers(), p.mode(), p.scheduling(), p.budget(), policy, score, value, verifier);
    }
    private static long work(NativeMoveSearch.Problem problem) {
        var sink = new Work();
        try (var scope = RetainedOperation.open(sink)) { NativeExecutionInventory.eligible(problem, null); }
        return sink.units;
    }
    private static final class Work implements RetainedOperation.Sink {
        long units;
        @Override public void executionWork(long n) { units += n; }
        @Override public void validationWork(long n) { units += n; }
        @Override public void checkpoint() {}
        @Override public void retainedReferences(RetainedGraph.Visitor v) {}
    }
    private enum Unknown implements NativeMovePriorityPolicy, ToDoubleFunction<TypedMoveSearch.State>, NativeStateValue,
            NativeVerifier, NativeVerifierProvider.ExecutionInventory, TypedSourceOnlySearch.Objective, RetainedGraph.View {
        INSTANCE;
        @Override public double score(NativeSearchMove m, TypedMoveSearch.State s, TypedMoveSearch.Context c) { throw new AssertionError(); }
        @Override public double applyAsDouble(TypedMoveSearch.State s) { throw new AssertionError(); }
        @Override public NativeStateValue.Assessment evaluate(TypedMoveSearch.State s, TypedMoveSearch.Context c) { throw new AssertionError(); }
        @Override public TypedSourceOnlySearch.Score evaluate(TypedMoveSearch.State s) { throw new AssertionError(); }
        @Override public NativeVerification verify(TypedMoveSearch.State s, NativeSearchMove m, TypedMoveSearch.Context c) { throw new AssertionError(); }
        @Override public String executionRevision(NativeMoveProvider p) { throw new AssertionError("caller-supplied marker consulted"); }
        @Override public void retainedReferences(RetainedGraph.Visitor v) {}
    }
}
