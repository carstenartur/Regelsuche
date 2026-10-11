package de.regelsuche.search.moves;

import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import de.regelsuche.search.program.AstTransportObservation;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeNodeCountObjectiveTest {
    private static final Expr X = new VariableExpr("x");
    private static Expr plusZero(Expr e) { return new BinaryExpr(e, BinaryOperator.ADD, new NumberExpr(0)); }
    private static TypedMoveSearch.State state(Expr e) { return new TypedMoveSearch.State(e, 0, 0, "", List.of(), Set.of(), 0); }
    private static NativeMoveSearch.Problem problem(long work) {
        return new NativeMoveSearch.Problem(plusZero(plusZero(X)), TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION),
            List.of(NativeExecutionInventoryTest.provider("zero")), MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(3, 3, 0, 16, work));
    }
    @Test void countsOccurrencesIncludingSharedSubtreesWithoutCodecTransport() {
        try (var codecs = AstTransportObservation.open()) {
            var score = NativeNodeCountObjective.INSTANCE.evaluate(state(new FunctionExpr("f", List.of(X, plusZero(X)))));
            assertEquals(5, score.value());
            assertEquals(5, score.work());
            assertEquals(0, codecs.total());
        }
    }
    @Test void adequateAndBestUseDifferentStoppingContractsInTheSameKernel() {
        var engine = NativeMoveSearch.boundedAccounting();
        var p = problem(1_000_000_000);
        var adequate = engine.searchUntil(p, NativeNodeCountObjective.INSTANCE, 3, SearchContinuationContract.PATH_SENSITIVE);
        var best = engine.searchBest(p, NativeNodeCountObjective.INSTANCE, SearchContinuationContract.PATH_SENSITIVE);
        assertTrue(adequate.withinBudget());
        assertTrue(best.withinBudget());
        assertEquals(5, adequate.inputScore());
        assertEquals(3, adequate.outputScore());
        assertEquals(1, adequate.witness().size());
        assertEquals(MoveSearch.Outcome.QUALITY_REACHED, adequate.search().outcome());
        assertEquals(1, best.outputScore());
        assertEquals(2, best.witness().size());
        assertNotEquals(MoveSearch.Outcome.QUALITY_REACHED, best.search().outcome());
        assertTrue(best.totalWork() > adequate.totalWork());
        assertTrue(adequate.replayWork() > 0 && best.replayWork() > 0);
    }
    @Test void qualitySelectionAlsoPaysItsFinalOverrun() {
        var engine = NativeMoveSearch.boundedAccounting();
        var ample = engine.searchUntil(problem(1_000_000_000), NativeNodeCountObjective.INSTANCE, 3, SearchContinuationContract.PATH_SENSITIVE);
        var overrun = engine.searchUntil(problem(ample.totalWork() - 1), NativeNodeCountObjective.INSTANCE, 3, SearchContinuationContract.PATH_SENSITIVE);
        assertFalse(overrun.withinBudget());
        assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, overrun.search().outcome());
        assertEquals(ample.witness(), overrun.witness());
    }
    @Test void abortTransfersAlreadyVisitedObjectiveWorkExactlyOnce() {
        var sink = new AbortObserver();
        try (var scope = RetainedOperation.open(sink)) {
            var failure = assertThrows(SearchExecution.ResourceLimit.class, () ->
                NativeNodeCountObjective.INSTANCE.evaluate(state(new FunctionExpr("f", List.of(X, plusZero(X))))));
            assertSame(sink.failure, failure);
            assertEquals(5, failure.takeWork().mechanical());
            assertEquals(0, failure.takeWork().total());
            assertTrue(sink.work > 0);
        }
        assertFalse(RetainedOperation.isObserved());
    }
    private static final class AbortObserver implements RetainedOperation.Sink {
        final SearchExecution.ResourceLimit failure = new SearchExecution.ResourceLimit();
        int checkpoints;
        long work;
        @Override public void executionWork(long n) { work += n; }
        @Override public void validationWork(long n) { work += n; }
        @Override public void checkpoint() { if (++checkpoints == 2) throw failure; }
        @Override public void retainedReferences(RetainedGraph.Visitor v) {}
    }
}
