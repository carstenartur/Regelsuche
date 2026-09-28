package de.regelsuche.search.moves;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedGraph;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeCapabilityRetentionTest {
    private record Value(Expr rewritten) implements NativeStateValue,RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(rewritten);}
        @Override public Assessment evaluate(TypedMoveSearch.State state,TypedMoveSearch.Context context){
            return new Assessment(1,0,1,0,Map.of("usable",new Capability("usable",state.expression(),"root",state.expression(),rewritten)));
        }
    }
    @Test void nativeCapabilityRetainsItsExactExpressionsThroughAdmissionAndResultOwnership(){
        var source=new VariableExpr("source");var replacement=new VariableExpr("replacement");
        var problem=new NativeMoveSearch.Problem(source,TypedMoveSearch.Context.frozen(source),List.of(),MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(1,1,0,10,1000000),NativeMovePriorityPolicy.INVENTORY_ORDER,
            NativeMoveSearch.ZeroScore.INSTANCE,new Value(replacement));
        var result=new NativeMoveSearch().search(problem,SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(MoveSearch.Outcome.TARGET_REACHED,result.observedOutcome(),result.accounting().detail());
        assertTrue(result.observationsComplete());assertFalse(result.accountingComplete());assertFalse(result.withinBudget());assertTrue(result.totalWork()<=result.workBudget());
        assertEquals(2,result.accounting().resultRetained().nodes());
        assertEquals(RetainedGraph.measure(result).retained(),result.accounting().resultRetained());
        var projected=result.exportLegacy(NativeMoveSearch.Result.DEFAULT_EXPORT_WORK,SearchExpressionStore.Limits.DEFAULT).projection();
        assertEquals(1,projected.stateAssessments().size());
        assertEquals("usable",projected.stateAssessments().values().iterator().next().capabilities().get("usable").providerId());
    }
}
