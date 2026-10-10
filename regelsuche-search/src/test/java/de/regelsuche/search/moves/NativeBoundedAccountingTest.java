package de.regelsuche.search.moves;

import de.regelsuche.ast.*;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.search.program.AstTransportObservation;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeBoundedAccountingTest {
    private static final VariableExpr X = new VariableExpr("x");
    private static final Expr INPUT = new BinaryExpr(X, BinaryOperator.ADD, new NumberExpr(0));
    private static NativeMoveSearch.Problem problem(long work) {
        return new NativeMoveSearch.Problem(INPUT, TypedMoveSearch.Context.frozen(X),
            List.of(NativeExecutionInventoryTest.provider("zero")), MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(1, 1, 0, 8, work));
    }
    private static NativeMoveSearch.Result run(long work) {
        return NativeMoveSearch.boundedAccounting().search(problem(work), SearchContinuationContract.PATH_SENSITIVE);
    }
    @Test void declaredExecutionIncludesFinalReplayAndDoesNotSerializeFrontierExpressions() {
        try (var codec = AstTransportObservation.open()) {
            var result = run(1_000_000);
            assertEquals(MoveSearch.Outcome.TARGET_REACHED, result.outcome());
            assertTrue(result.accountingComplete());
            assertTrue(result.withinBudget());
            assertEquals(NativeMoveSearch.QUALIFIED_REVISION, result.workRevision());
            assertEquals(NativeMoveSearch.Coverage.COMPLETE_DECLARED_EXECUTION, result.coverage());
            assertSame(X, result.output());
            assertEquals(1, result.witness().size());
            assertTrue(result.witness().getFirst().verification().accepted());
            assertTrue(result.replayWork() > 0);
            assertEquals(0, codec.total());
            var a = result.accounting();
            assertEquals(new RetainedGraph.Usage(0, 0, 0), a.live());
            assertEquals(result.metrics().totalWork() + result.replayWork() + a.validationWork()
                + a.executionWork() + a.storageWork() + a.retentionWork(), result.totalWork());
        }
    }
    @Test void finalWorkCanExhaustTheBudgetWithoutErasingTheAlreadyCheckedWitness() {
        var ample = run(1_000_000);
        var shortByOne = run(ample.totalWork() - 1);
        assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, shortByOne.outcome());
        assertTrue(shortByOne.accountingComplete());
        assertFalse(shortByOne.withinBudget());
        assertEquals(ample.witness(), shortByOne.witness());
        assertEquals(ample.totalWork(), shortByOne.totalWork());
    }
    @Test void retentionFailureRemainsPaidAndInconclusive() {
        var result = NativeMoveSearch.boundedAccounting().search(problem(1_000_000), SearchContinuationContract.PATH_SENSITIVE,
            new SearchExpressionStore.Limits(2, 1_000_000, 1_000_000, 0));
        assertFalse(result.accountingComplete());
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE, result.outcome());
        assertTrue(result.totalWork() > 0);
        assertEquals(new RetainedGraph.Usage(0, 0, 0), result.accounting().live());
    }
    @Test void anObservableUnknownScoreDoesNotAcquireExecutionAuthority() {
        var p = problem(1_000_000);
        var unknown = new NativeMoveSearch.Problem(p.source(), p.context(), p.providers(), p.mode(), p.scheduling(), p.budget(),
            p.policy(), BenignUnknown.INSTANCE, p.stateValue(), p.verifier());
        var result = NativeMoveSearch.boundedAccounting().search(unknown, SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(MoveSearch.Outcome.TARGET_REACHED, result.observedOutcome());
        assertTrue(result.observationsComplete());
        assertFalse(result.accountingComplete());
        assertFalse(result.withinBudget());
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE, result.outcome());
    }
    @Test void defaultV7RemainsPartialAndDoesNotPayOptInInspection() {
        var p = problem(1_000_000);
        var old = new NativeMoveSearch().search(p, SearchContinuationContract.PATH_SENSITIVE);
        var qualified = NativeMoveSearch.boundedAccounting().search(p, SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(NativeMoveSearch.REVISION, old.workRevision());
        assertFalse(old.accountingComplete());
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE, old.outcome());
        assertEquals(old.witness(), qualified.witness());
        assertTrue(qualified.totalWork() > old.totalWork());
    }
    private enum BenignUnknown implements java.util.function.ToDoubleFunction<TypedMoveSearch.State>, RetainedGraph.View {
        INSTANCE;
        @Override public double applyAsDouble(TypedMoveSearch.State state) { return 0; }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {}
    }
}
