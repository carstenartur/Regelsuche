package de.regelsuche.search.moves;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeExternalRetentionTest {
    private static final class Capture implements TypedSourceOnlySearch.Objective,
            NativeStateValue,RetainedGraph.View {
        private final ArrayList<Expr> cache=new ArrayList<>();
        private void fill(){if(cache.isEmpty())for(int i=0;i<20;i++)cache.add(new VariableExpr("caller"+i));}
        @Override public TypedSourceOnlySearch.Score evaluate(TypedMoveSearch.State state){fill();return new TypedSourceOnlySearch.Score(0,1);}
        @Override public NativeStateValue.Assessment evaluate(TypedMoveSearch.State state,TypedMoveSearch.Context context){fill();return new NativeStateValue.Assessment(0,0,1,0,java.util.Map.of());}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(cache);}
    }
    private static NativeMoveSearch.Problem problem(boolean quality,Capture capture){
        var source=new VariableExpr("x");
        var context=quality?TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION):TypedMoveSearch.Context.frozen(source);
        return new NativeMoveSearch.Problem(source,context,List.of(),MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(1,1,0,10,1000000),NativeMovePriorityPolicy.INVENTORY_ORDER,
            NativeMoveSearch.ZeroScore.INSTANCE,quality?NativeStateValue.NONE:capture);
    }
    @Test void completedAndAbortedRunsReportTheActualStillCallerOwnedCacheAfterSessionClose(){
        for(boolean quality:List.of(false,true))for(long limit:List.of(10L,100L)) {
            var capture=new Capture();var problem=problem(quality,capture);var engine=new NativeMoveSearch();
            var limits=new SearchExpressionStore.Limits(limit,1000000,1000000,0);
            var result=quality?engine.searchUntil(problem,capture,0,SearchContinuationContract.PATH_SENSITIVE,limits).search():
                engine.search(problem,SearchContinuationContract.PATH_SENSITIVE,limits);
            assertEquals(20,capture.cache.size(),"the caller's graph must not be cleared at session close");
            var external=assertDoesNotThrow(()->result.accounting().externalRetained()).orElseThrow();
            assertEquals(21,external.nodes());assertEquals(1,result.accounting().resultRetained().nodes());
            assertEquals(new RetainedGraph.Usage(0,0,0),result.accounting().live());
            RetainedGraph.View roots=v->{v.reference(problem);v.reference(quality?capture:null);};
            assertEquals(RetainedGraph.measure(roots).retained(),external);
            assertEquals(limit==100,result.accountingComplete());assertEquals(limit==100,result.withinBudget());
            assertTrue(result.accounting().retentionWork()>RetainedGraph.measure(roots).work());
        }
    }
    @Test void unknownCallerCaptureIsNotReportedAsZeroOrComplete(){
        var problem=problem(true,new Capture());
        var result=new NativeMoveSearch().searchUntil(problem,state->new TypedSourceOnlySearch.Score(0,1),0,
            SearchContinuationContract.PATH_SENSITIVE);
        assertFalse(result.search().accountingComplete());assertFalse(result.withinBudget());
        assertTrue(assertDoesNotThrow(()->result.search().accounting().externalRetained()).isEmpty());
        assertTrue(result.totalWork()>0);assertFalse(result.hasIncumbent());
    }
}
