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
        var reference=RetainedGraph.measure(root);
        assertEquals(reference.retained(),second.retained(),"the independent fresh scanner includes actual inventory metadata and alias union");
        assertEquals(reference.objects(),second.objects());
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

    @Test void failedPeakCannotActivateStagedEntriesAndStillChargesTheirBuildAndRelease(){
        var leaf=new VariableExpr("x");var inventory=new RetainedGraph.Inventory();var root=new Root(leaf,inventory);
        var attempted=inventory.measure(root,new RetainedGraph.Usage(0,100,100_000));
        assertEquals(1,attempted.peak().nodes());assertTrue(attempted.work()>0);
        assertEquals(0,inventory.cachedVertices());
        assertEquals(RetainedGraph.measure(root).retained(),attempted.retained());
        var retry=inventory.measure(root);assertEquals(RetainedGraph.measure(root).retained(),retry.retained());
        assertTrue(inventory.cachedVertices()>0);inventory.close();
    }
    @Test void cacheOnlyGraphOverlapsBeforePruningAndNeverBecomesPrimaryAgain(){
        var values=new ArrayList<Expr>();values.add(new BinaryExpr(new VariableExpr("x"),BinaryOperator.ADD,new VariableExpr("y")));
        var inventory=new RetainedGraph.Inventory();var root=new Root(values,inventory);inventory.measure(root);
        values.clear();var released=inventory.measure(root,new RetainedGraph.Usage(1,100,100_000));
        assertEquals(3,released.peak().nodes(),"the still-owned cache graph exists before pruning");
        assertEquals(0,released.retained().nodes());assertEquals(0,inventory.cachedVertices());
        assertEquals(RetainedGraph.measure(root).retained(),released.retained());inventory.close();
    }
    @Test void idsAreNotReusedAfterPruneAndClosedBookkeepingIsActuallyReleased(){
        var values=new ArrayList<Object>(List.of(new VariableExpr("x")));
        var inventory=new RetainedGraph.Inventory(2,2,2);var root=new Root(values,inventory);
        inventory.measure(root);assertEquals(2,inventory.cachedVertices());values.clear();inventory.measure(root);
        values.add(new VariableExpr("y"));var fallback=inventory.measure(root);
        assertEquals(0,inventory.cachedVertices(),"both monotone IDs remain tombstones; later objects use fresh traversal");
        assertEquals(RetainedGraph.measure(root).retained(),fallback.retained());
        assertTrue(inventory.close()>0);
        assertEquals(new RetainedGraph.Usage(0,0,2),RetainedGraph.measure(inventory).retained());
    }

    @Test void privateMetadataTotalsNeedOnlyTheOneActuallyAllocatedScanner(){
        var inventory=new RetainedGraph.Inventory(1,1,1);var root=new Root(null,inventory);
        var measured=inventory.measure(root);
        // Root/owner/backend/containers retain8 slots. One scanner has5 base slots,
        // seven own fields,2 mask entries and4 seen slots (two identities):18.
        // The removed MetadataScan does not exist and incurs neither storage nor work.
        assertEquals(new RetainedGraph.Usage(0,0,8),measured.retained());
        assertEquals(26,measured.peak().references());inventory.close();
        var direct=new RetainedGraph.Inventory(1,1,1);
        var bounded=direct.measure(direct,new RetainedGraph.Usage(0,0,21));
        assertEquals(new RetainedGraph.Usage(0,0,6),bounded.retained());
        assertEquals(22,bounded.peak().references(),"no wrapper: two fewer retained and two fewer seen slots");
        assertTrue(bounded.peak().references()>21);direct.close();
    }

    @Test void publicViewAliasesOfActualInventoryMetadataHaveOneIdentityContribution(){
        var inventory=new RetainedGraph.Inventory(1,1,1);var values=new ArrayList<Object>();
        inventory.retainedReferences(new RetainedGraph.Visitor(){
            @Override public void reference(Object value){values.add(value);}
            @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
        });
        var root=new Object[]{inventory,values.getFirst()};
        var measured=inventory.measure(root);var reference=RetainedGraph.measure(root);
        assertEquals(new RetainedGraph.Usage(0,0,8),reference.retained());assertEquals(5,reference.objects());
        assertEquals(reference.retained(),measured.retained());assertEquals(reference.objects(),measured.objects());
        inventory.close();
    }

    @Test void nonemptyPublicMetadataAndArrayAliasesUsePaidFreshFallbackWithoutClosingSession(){
        for(boolean arrayAlias:List.of(false,true)){
            var inventory=new RetainedGraph.Inventory();var leaf=new VariableExpr("x");
            inventory.measure(new Root(leaf,inventory));
            var objects=metadataObjects(inventory);
            Object alias=arrayAlias?objects.stream().filter(value->value instanceof int[] ids && ids.length>0).findFirst().orElseThrow():objects.get(1);
            var root=new Root(List.of(leaf,alias),inventory);var measured=inventory.measure(root);
            var reference=RetainedGraph.measure(root);
            assertEquals(reference.retained(),measured.retained());assertEquals(reference.objects(),measured.objects());
            assertTrue(measured.work()>reference.work());assertEquals(0,inventory.cachedVertices());
            var laterRoot=new Root(leaf,inventory);var later=inventory.measure(laterRoot);
            assertEquals(RetainedGraph.measure(laterRoot).retained(),later.retained());
            assertTrue(inventory.cachedVertices()>0,"fallback prunes but does not close the inventory");inventory.close();
        }
    }
    @Test void aliasedMetadataFailureKeepsPaidWorkAndCannotLeaveReusableEntries(){
        var inventory=new RetainedGraph.Inventory();var leaf=new VariableExpr("x");inventory.measure(new Root(leaf,inventory));
        Object data=metadataObjects(inventory).get(1);
        var failure=assertThrows(RetainedGraph.Unmeasured.class,()->inventory.measure(new Object[]{inventory,data,leaf,new Object()}));
        assertTrue(failure.attempted().work()>0);assertEquals(0,inventory.cachedVertices());
        var valid=new Root(leaf,inventory);assertEquals(RetainedGraph.measure(valid).retained().nodes(),inventory.measure(valid).retained().nodes());inventory.close();
    }
    private static List<Object> metadataObjects(RetainedGraph.Inventory inventory){
        var pending=new ArrayDeque<Object>();var seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());var result=new ArrayList<Object>();
        var visitor=new RetainedGraph.Visitor(){
            @Override public void reference(Object value){if(value!=null)pending.addLast(value);}
            @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
        };
        pending.add(inventory);
        while(!pending.isEmpty()){
            Object value=pending.removeFirst();if(!seen.add(value))continue;result.add(value);
            if(value instanceof RetainedGraph.View view)view.retainedReferences(visitor);
            else if(value instanceof Map<?,?> map)map.forEach((key,item)->{visitor.reference(key);visitor.reference(item);});
            else if(value instanceof Collection<?> collection)collection.forEach(visitor::reference);
        }
        return result;
    }

    @Test void failedFreshAliasFallbackPreservesEarlierObservedNodeAndCharacterPeaks(){
        var inventory=new RetainedGraph.Inventory();Object data=metadataObjects(inventory).get(1);
        var failure=assertThrows(RetainedGraph.Unmeasured.class,
            ()->inventory.measure(new Object[]{inventory,data,new Object(),new VariableExpr("x")}));
        assertTrue(failure.attempted().peak().nodes()>=1,"the first scanner really visited x after recording the earlier unknown");
        assertTrue(failure.attempted().peak().characters()>=1,"the later fresh scanner cannot erase already observed text");
        assertTrue(failure.attempted().work()>0);assertEquals(0,inventory.cachedVertices());inventory.close();
    }

    @Test void tinyPrivateMetadataHasIndependentlyCountedSlotsAndObjects(){
        var inventory=new RetainedGraph.Inventory(2,2,2);var root=new Root(new VariableExpr("x"),inventory);
        inventory.measure(root);var measured=inventory.measure(root);
        // Two live vertices/IDs, two child arrays (one entry in total), two one-word masks:
        // metadata slots5 + index4 + rows2 + vertex fields6 + child1 + masks2 =20.
        // Root/leaf payload adds5 slots. Four primary objects + backend/index/rows3
        // + vertices2 + child arrays2 + masks2 =13 objects.
        assertEquals(new RetainedGraph.Usage(1,1,25),measured.retained());assertEquals(13,measured.objects());
        assertEquals(RetainedGraph.measure(root).retained(),measured.retained());inventory.close();
    }

}
