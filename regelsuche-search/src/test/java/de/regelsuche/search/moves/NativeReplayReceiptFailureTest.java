package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.transform.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real native entrypoints: the verifier returns successfully before the new receipt exceeds retention. */
class NativeReplayReceiptFailureTest {
    private static final class Checker implements NativeVerifier,RetainedGraph.View {
        final NativeMoveSearch.Primitive primitive;
        final List<Expr> finishedAllocation = new ArrayList<>();
        int calls;
        long returnedWork;
        Checker(NativeMoveSearch.Primitive primitive) { this.primitive = primitive; }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(primitive); visitor.reference(finishedAllocation); }
        @Override public NativeVerification verify(TypedMoveSearch.State state,NativeSearchMove move,TypedMoveSearch.Context context) {
            var checked = primitive.verify(state, move, context);
            if (++calls != 2) return checked;
            var step = ((NativeMoveProof.Primitive) checked.checkedProof()).step();
            var freshStep = new AstRewriteTransport.Step(copy(step.source()), copy(step.target()), step.rule(), step.kind(),
                step.mayIncreaseComplexity(), step.estimatedCostDelta(), step.equivalencePreservingByConstruction(),
                step.assumptions(), step.packId(), step.license());
            var returned = new NativeVerification(checked.accepted(), checked.work(), new NativeMoveProof.Primitive(freshStep), checked.ruleId(), checked.reason());
            returnedWork = returned.work();
            // There is deliberately no checkpoint in the completed verifier after this allocation.
            // The next paid replay comparison (or its explicit receipt owner) must account for it.
            for (int i = 0; i < 50; i++) finishedAllocation.add(new VariableExpr("replay-only-" + i));
            return returned;
        }
    }
    private static Expr copy(Expr expression) {
        return switch (expression) {
            case VariableExpr variable -> new VariableExpr(variable.name());
            case NumberExpr number -> new NumberExpr(number.value());
            case BinaryExpr binary -> new BinaryExpr(copy(binary.left()), binary.operator(), copy(binary.right()));
            default -> throw new AssertionError("unexpected fixture expression");
        };
    }
    private enum Objective implements TypedSourceOnlySearch.Objective,RetainedGraph.View { INSTANCE;
        @Override public TypedSourceOnlySearch.Score evaluate(TypedMoveSearch.State state) { return new TypedSourceOnlySearch.Score(state.searchDepth() == 0 ? 1 : 0, 1); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {}
    }
    private static void verify(boolean sourceOnly) {
        var pattern = PatternExpr.var("a");
        var rule = new PatternRewriteRule("zero", PatternExpr.op(BinaryOperator.ADD, pattern, PatternExpr.num(0)), pattern);
        var target = new VariableExpr("x");
        var source = new BinaryExpr(target, BinaryOperator.ADD, new NumberExpr(0));
        var descriptor = new MoveProvider.Descriptor("zero", "zero", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "zero-v1");
        var primitive = new NativeMoveSearch.Primitive(descriptor, new AstRewriteTransport(List.of(rule), 64, 128));
        var checker = new Checker(primitive);
        var context = sourceOnly ? TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION) : TypedMoveSearch.Context.frozen(target);
        var problem = new NativeMoveSearch.Problem(source, context, List.of(primitive), MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(1, 1, 0, 10, 10_000_000), NativeMovePriorityPolicy.INVENTORY_ORDER,
            NativeMoveSearch.ZeroScore.INSTANCE, NativeStateValue.NONE, checker);
        var limits = new SearchExpressionStore.Limits(30, 1_000_000, 1_000_000, 0);
        NativeMoveSearch.Result result;
        if (sourceOnly) {
            var quality = assertDoesNotThrow(() -> new NativeMoveSearch().searchUntil(problem, Objective.INSTANCE, 0, SearchContinuationContract.PATH_SENSITIVE, limits));
            result = quality.search();
            assertTrue(quality.hasIncumbent());
            assertEquals(1, quality.witness().size());
        } else result = assertDoesNotThrow(() -> new NativeMoveSearch().search(problem, SearchContinuationContract.PATH_SENSITIVE, limits));
        assertEquals(2, checker.calls, "the independent verifier actually returned its second receipt");
        assertEquals(checker.returnedWork, result.replayWork(), "completed replay verification is still paid exactly once");
        assertTrue(result.replayWork() > 0);
        assertEquals(1, result.witness().size());
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE, result.observedOutcome());
        assertFalse(result.observationsComplete());
        assertFalse(result.withinBudget());
        assertEquals("NATIVE_RETENTION_EXHAUSTED", result.accounting().detail());
        assertTrue(result.accounting().peak().nodes() >= 50);
        assertEquals(new RetainedGraph.Usage(0, 0, 0), result.accounting().live());
    }
    @Test void targetSearchKeepsItsAttemptAfterCompletedReplayReceiptExceedsRetention() { verify(false); }
    @Test void qualitySearchKeepsItsAttemptAfterCompletedReplayReceiptExceedsRetention() { verify(true); }
}
