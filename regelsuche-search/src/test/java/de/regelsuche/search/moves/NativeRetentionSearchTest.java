package de.regelsuche.search.moves;

import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedGraph;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeRetentionSearchTest {
    @Test void paidOwnershipWorkParticipatesInTheExistingBudgetAndOpaqueCallbacksFailClosed() {
        var expression=new VariableExpr("x");
        var small=new NativeMoveSearch.Problem(expression,TypedMoveSearch.Context.frozen(expression),List.of(),
            MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(1,1,0,10,1));
        var overrun=new NativeMoveSearch().search(small,SearchContinuationContract.PATH_SENSITIVE,SearchExpressionStore.Limits.DEFAULT);
        assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED,overrun.outcome());assertFalse(overrun.withinBudget());
        assertTrue(overrun.accounting().retentionWork()>1);
        var opaque=new NativeMoveSearch.Problem(expression,small.context(),List.of(),small.mode(),small.scheduling(),
            new MoveSearch.Budget(1,1,0,10,100000),NativeMovePriorityPolicy.INVENTORY_ORDER,s->0,NativeStateValue.NONE);
        var unknown=new NativeMoveSearch().search(opaque,SearchContinuationContract.PATH_SENSITIVE,SearchExpressionStore.Limits.DEFAULT);
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE,unknown.outcome());assertFalse(unknown.accountingComplete());
        assertTrue(unknown.accounting().detail().startsWith("NATIVE_RETENTION_UNSUPPORTED:"));
        assertTrue(unknown.accounting().retentionWork()>0);
    }
    @Test void totalRunLimitIncludesTheKernelAndItsAccountingAndFailureRetainsPaidWork() {
        var expression=new VariableExpr("x");
        var problem=new NativeMoveSearch.Problem(expression,TypedMoveSearch.Context.frozen(expression),List.of(),
            MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(1,1,0,10,100000));
        var rejected=new NativeMoveSearch().search(problem,SearchContinuationContract.PATH_SENSITIVE,
            new SearchExpressionStore.Limits(1,2,4,0));
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE,rejected.outcome());
        assertEquals("NATIVE_RETENTION_EXHAUSTED",rejected.accounting().detail());
        assertFalse(rejected.accountingComplete());assertFalse(rejected.withinBudget());
        assertTrue(rejected.accounting().retentionWork()>0);assertTrue(rejected.totalWork()>0);
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE,rejected.exportLegacy().outcome());
        var accepted=new NativeMoveSearch().search(problem,SearchContinuationContract.PATH_SENSITIVE,SearchExpressionStore.Limits.DEFAULT);
        assertEquals(MoveSearch.Outcome.TARGET_REACHED,accepted.outcome());assertTrue(accepted.withinBudget());
        assertTrue(accepted.accounting().validationWork()>0);assertTrue(accepted.accounting().storageWork()>0);
        assertTrue(accepted.accounting().retentionWork()>0);
        assertEquals(new RetainedGraph.Usage(0,0,0),accepted.accounting().live(),"closed session retains no lookup graph");
        assertEquals(1,accepted.accounting().resultRetained().nodes());
        assertTrue(accepted.accounting().peak().references()>accepted.accounting().resultRetained().references());
        assertSame(expression,accepted.output(),"immutable result remains usable after lookup indexes close");
    }
}
