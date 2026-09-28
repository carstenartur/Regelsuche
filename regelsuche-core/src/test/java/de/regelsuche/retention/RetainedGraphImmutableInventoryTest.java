package de.regelsuche.retention;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.symbol.SymbolId;
import java.util.*;
import org.junit.jupiter.api.Test;

class RetainedGraphImmutableInventoryTest {
    private record Root(Object value, RetainedGraph.Inventory inventory) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(value);visitor.reference(inventory);}
    }
    @Test void repeatedImmutableGraphAvoidsActualRepeatedTraversalWithAllMetadataStillOwned(){
        var shared=new VariableExpr("x");Expr expression=shared;
        for(int i=0;i<12;i++)expression=new BinaryExpr(expression,BinaryOperator.ADD,shared);
        var inventory=new RetainedGraph.Inventory();var root=new Root(expression,inventory);
        var first=inventory.measure(root);var second=inventory.measure(root);
        assertEquals(RetainedGraph.measure(root).retained(),second.retained(),"the independent fresh scanner includes actual inventory metadata and alias union");
        assertTrue(first.work()>0 && second.work()>0);
        assertTrue(second.work()<first.work(),"a real warm lookup removes repeated immutable traversal; its index and cleanup are still paid");
        assertTrue(inventory.cachedVertices()>0);
        assertTrue(inventory.close()>0);assertEquals(0,inventory.close());
        assertEquals(0,RetainedGraph.measure(inventory).retained().nodes());
    }
    @Test void mutableOwnerChangesAndEqualButDistinctIdentitiesRemainVisible(){
        String name=new String("shared");
        var symbol=new SymbolId(new UUID(1,2),1);
        var values=new ArrayList<Object>(List.of(new VariableExpr(name),new VariableExpr(name),
            new VariableExpr(new String("shared")),VariableExpr.scoped(symbol),VariableExpr.scoped(symbol)));
        var inventory=new RetainedGraph.Inventory();var root=new Root(values,inventory);
        inventory.measure(root);
        values.removeFirst();values.add(new FunctionExpr("f",List.of(new VariableExpr("later"))));
        var changed=inventory.measure(root);
        assertEquals(RetainedGraph.measure(root).retained(),changed.retained());
        values.clear();var cleared=inventory.measure(root);
        assertEquals(RetainedGraph.measure(root).retained(),cleared.retained());
        assertEquals(0,cleared.retained().nodes(),"cache-only reachability does not preserve primary liveness after pruning");
        inventory.close();
    }
    @Test void capacityFallbackAndSessionCloseCannotChangeAnotherOwner(){
        var leaf=new VariableExpr("x");var limited=new RetainedGraph.Inventory(1,1,1);
        var other=new RetainedGraph.Inventory();var root=new Root(leaf,limited);var otherRoot=new Root(leaf,other);
        var first=limited.measure(root);other.measure(otherRoot);
        assertEquals(RetainedGraph.measure(root).retained(),first.retained());assertTrue(limited.cachedVertices()<=1);
        limited.close();assertThrows(IllegalStateException.class,()->limited.measure(root));
        assertEquals(RetainedGraph.measure(otherRoot).retained(),other.measure(otherRoot).retained());
        other.close();
    }
    @Test void unknownPayloadAndBigIntegerSubtypeCannotGainAnImmutableCapability(){
        class OpaqueInteger extends java.math.BigInteger {final Expr capture=new VariableExpr("hidden");OpaqueInteger(){super("1");}}
        var inventory=new RetainedGraph.Inventory();
        assertThrows(RetainedGraph.Unmeasured.class,()->inventory.measure(new Root(new Object(),inventory)));
        assertThrows(RetainedGraph.Unmeasured.class,()->inventory.measure(new Root(new OpaqueInteger(),inventory)));
        assertEquals(0,inventory.cachedVertices());assertTrue(inventory.close()>0);
    }
    @Test void failedWholeObservationCannotActivateEarlierValidStagedVertices(){
        var inventory=new RetainedGraph.Inventory();var leaf=new VariableExpr("visited-first");
        var rejected=assertThrows(RetainedGraph.Unmeasured.class,
            ()->inventory.measure(new Root(List.of(leaf,new Object()),inventory)));
        assertTrue(rejected.attempted().retained().nodes()>=1,"the real AST was visited before the unknown payload");
        assertTrue(rejected.attempted().work()>0);assertEquals(0,inventory.cachedVertices());
        var root=new Root(leaf,inventory);var next=inventory.measure(root);
        assertEquals(RetainedGraph.measure(root).retained(),next.retained());inventory.close();
    }
    @Test void functionArgumentsAlsoOwnedOutsideTheFunctionKeepExactIdentityUnion(){
        var x=new VariableExpr("x");var function=new FunctionExpr("f",List.of(x,x,new VariableExpr(new String("x"))));
        for(var values:List.of(List.of(function,function.arguments()),List.of(function.arguments(),function))){
            var inventory=new RetainedGraph.Inventory();var root=new Root(values,inventory);
            inventory.measure(root);var warm=inventory.measure(root);
            assertEquals(RetainedGraph.measure(root).retained(),warm.retained());inventory.close();
        }
    }
    @Test void missingInventoryOwnerFailsClosedWithItsAttemptedPaidReceipt(){
        var inventory=new RetainedGraph.Inventory();
        var failure=assertThrows(RetainedGraph.InventoryFailure.class,()->inventory.measure(new VariableExpr("x")));
        assertTrue(failure.attempted().work()>0);assertEquals(0,inventory.cachedVertices());
        assertEquals(0,RetainedGraph.measure(inventory).retained().nodes());inventory.close();
    }
    @Test void foreignInventoryOwnershipRemainsVisibleAndCloseIsIsolated(){
        var first=new RetainedGraph.Inventory();var second=new RetainedGraph.Inventory();
        var leaf=new VariableExpr("shared");first.measure(new Root(leaf,first));
        var outer=new Root(List.of(first,leaf),second);second.measure(outer);
        assertEquals(RetainedGraph.measure(outer).retained(),second.measure(outer).retained());
        first.close();assertEquals(RetainedGraph.measure(outer).retained(),second.measure(outer).retained());
        second.close();
    }

}
