package de.regelsuche.search.moves;

import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeImmutableInventorySessionTest {
    @Test void ordinarySessionPaysFreshInspectionWithoutOpeningTheLosingPrototype(){
        var source=new VariableExpr("x");
        var problem=new NativeMoveSearch.Problem(source,TypedMoveSearch.Context.frozen(source),List.of(),
            MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(1,1,0,10,1_000_000));
        try(var store=new SearchExpressionStore(SearchExpressionStore.Limits.DEFAULT)){
            var session=new NativeRetentionSession(problem,store,SearchExpressionStore.Limits.DEFAULT);
            session.retainedReferences(new RetainedGraph.Visitor(){
                @Override public void reference(Object value){assertFalse(value instanceof RetainedGraph.Inventory);}
                @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
            });
            try(var scope=RetainedOperation.open(session)){
                session.operation(scope);
                long expected=RetainedGraph.measure(session).work(),before=session.work();
                session.checkpoint();
                assertEquals(expected,session.work()-before,"ordinary inspection still pays the complete independent fresh scan");
            }
        }
        assertEquals(0,RetainedOperation.observedWork());
    }
    @Test void actualSessionOwnsItsInventoryUntilHandoffThenPaysAndReleasesIt(){
        var source=new VariableExpr("x");
        var problem=new NativeMoveSearch.Problem(source,TypedMoveSearch.Context.frozen(source),List.of(),
            MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(1,1,0,10,1_000_000));
        var result=new NativeMoveSearch().search(problem,SearchContinuationContract.PATH_SENSITIVE);
        try(var store=new SearchExpressionStore(SearchExpressionStore.Limits.DEFAULT)){
            var session=new NativeRetentionSession(problem,store,SearchExpressionStore.Limits.DEFAULT,true);
            var owned=new RetainedGraph.Inventory[1];
            session.retainedReferences(new RetainedGraph.Visitor(){
                @Override public void reference(Object value){if(value instanceof RetainedGraph.Inventory inventory)owned[0]=inventory;}
                @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
            });
            assertNotNull(owned[0],"the actual session has a visible own inventory edge");
            try(var scope=RetainedOperation.open(session)){
                session.operation(scope);session.checkpoint();assertTrue(owned[0].cachedVertices()>0);
                long before=session.work();session.finish(result);
                assertTrue(session.work()>before);assertEquals(0,owned[0].cachedVertices());
                assertEquals(new RetainedGraph.Usage(0,0,2),RetainedGraph.measure(owned[0]).retained());
            }
            assertEquals(RetainedGraph.measure(result).retained(),result.accounting().resultRetained());
            assertEquals(new RetainedGraph.Usage(0,0,0),result.accounting().live());
            assertTrue(result.observationsComplete());assertFalse(result.accountingComplete());
        }
    }
}
