package de.regelsuche.canonical;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.assumption.*;
import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CanonicalizerWorkBoundaryTest {
    private enum Boundary { BUCKET, FUNCTION, ADD_ALL, SNAPSHOT }
    private static final class Stop extends RuntimeException {}
    private static final class Sink implements RetainedOperation.Sink {
        final Boundary boundary;RetainedOperation scope;FunctionExpr source,expectedFunction;
        long work,lastCheckpoint,atBoundary;List<?> snapshot;
        Sink(Boundary boundary){this.boundary=boundary;}
        @Override public void executionWork(long units){work=Math.addExact(work,units);}
        @Override public void validationWork(long units){work=Math.addExact(work,units);}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(scope);}
        @Override public void checkpoint(){
            RetainedGraph.measure(scope);
            boolean stop=boundary==Boundary.ADD_ALL;
            var pending=new ArrayDeque<Object>();var seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var visitor=new RetainedGraph.Visitor(){
                @Override public void reference(Object value){if(value!=null)pending.addLast(value);}
                @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
            };
            visitor.reference(scope);List<?> foundSnapshot=null;
            while(!pending.isEmpty()){
                var value=pending.removeFirst();if(!seen.add(value))continue;
                if(boundary==Boundary.BUCKET && value instanceof Map<?,?> map && map.containsKey("f(x)")
                        && map.get("f(x)") instanceof RetainedGraph.View)stop=true;
                if(boundary==Boundary.FUNCTION && value instanceof FunctionExpr function && value!=source && function.equals(expectedFunction))stop=true;
                if(boundary==Boundary.SNAPSHOT && value instanceof List<?> list && !(value instanceof ArrayList<?>)
                        && list.size()==1 && list.getFirst() instanceof Assumption)foundSnapshot=list;
                if(value instanceof RetainedGraph.View view)view.retainedReferences(visitor);
                else if(value instanceof Map<?,?> map)map.forEach((key,item)->{visitor.reference(key);visitor.reference(item);});
                else if(value instanceof Collection<?> collection)collection.forEach(visitor::reference);
                else if(value instanceof Object[] array)for(var item:array)visitor.reference(item);
                else if(value instanceof Optional<?> optional)optional.ifPresent(visitor::reference);
                else if(value instanceof FunctionExpr function)visitor.reference(function.arguments());
                else if(value instanceof BinaryExpr binary){visitor.reference(binary.left());visitor.reference(binary.right());}
            }
            if(foundSnapshot!=null){if(snapshot==foundSnapshot)stop=true;else snapshot=foundSnapshot;}
            if(stop){atBoundary=work-lastCheckpoint;throw new Stop();}
            lastCheckpoint=work;
        }
    }
    private static void released(Sink sink){assertEquals(0,RetainedGraph.measure(sink.scope).retained().nodes());assertTrue(sink.work>=sink.atBoundary);}
    @Test void completedBucketInsertionIsPaidBeforeItsFirstNestedCheckpoint(){
        var term=new FunctionExpr("f",List.of(new VariableExpr("x")));
        var source=new BinaryExpr(term,BinaryOperator.ADD,term);var sink=new Sink(Boundary.BUCKET);
        try(var scope=RetainedOperation.open(sink)){sink.scope=scope;assertThrows(Stop.class,()->new ExpressionCanonicalizer().canonicalize(source));}
        assertEquals(3,sink.atBoundary,"one completed lookup/insert plus two nested frame-acquisition units");released(sink);
    }
    @Test void functionArgumentCopyIsPaidBeforeTheNewFunctionCheckpoint(){
        var source=new FunctionExpr("f",List.of(new VariableExpr("x"),new VariableExpr("y"),
            new BinaryExpr(new VariableExpr("z"),BinaryOperator.ADD,new NumberExpr(0))));
        var sink=new Sink(Boundary.FUNCTION);sink.source=source;
        sink.expectedFunction=new FunctionExpr("f",List.of(new VariableExpr("x"),new VariableExpr("y"),new VariableExpr("z")));
        try(var scope=RetainedOperation.open(sink)){sink.scope=scope;assertThrows(Stop.class,()->new ExpressionCanonicalizer().canonicalize(source));}
        assertEquals(6,sink.atBoundary,"three copied arguments plus produced and frame acquisition");released(sink);
    }
    @Test void assumptionTransferPaysTheConsumedEntryBeforeNestedAddCanAbort(){
        var context=new AssumptionContext();var sink=new Sink(Boundary.ADD_ALL);
        var values=List.of(Assumption.nonZero("x"),Assumption.nonZero("y"));
        try(var scope=RetainedOperation.open(sink)){sink.scope=scope;assertThrows(Stop.class,()->context.addAll(values));}
        assertEquals(4,sink.atBoundary,"scope open, one actual iterator consumption and the first add frame");
        assertTrue(context.isEmpty(),"the first add checkpoint aborts before insertion or later entries");released(sink);
    }
    @Test void canonicalizerDoesNotPayForAnImaginarySecondSnapshotCopy(){
        var x=new VariableExpr("x");var source=new BinaryExpr(new BinaryExpr(x,BinaryOperator.DIV,x),BinaryOperator.MUL,new VariableExpr("y"));
        var context=new AssumptionContext();var sink=new Sink(Boundary.SNAPSHOT);
        try(var scope=RetainedOperation.open(sink)){sink.scope=scope;assertThrows(Stop.class,()->new ExpressionCanonicalizer().canonicalize(source,context));}
        assertEquals(10,sink.atBoundary,"two finished snapshot frames and one caller frame; no second list copy");
        assertNotNull(sink.snapshot);assertTrue(context.isEmpty());released(sink);
    }
}
