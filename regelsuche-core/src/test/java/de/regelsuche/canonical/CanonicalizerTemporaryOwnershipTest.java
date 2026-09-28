package de.regelsuche.canonical;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.assumption.AssumptionContext;
import de.regelsuche.retention.*;
import de.regelsuche.scalar.ExactRational;
import java.util.*;
import org.junit.jupiter.api.Test;

class CanonicalizerTemporaryOwnershipTest {
    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;Expr source;long work;
        boolean functionArguments,termContributions,factorBuckets,discardedPower,abortAtContributions;
        @Override public void executionWork(long units){work=Math.addExact(work,units);}
        @Override public void validationWork(long units){work=Math.addExact(work,units);}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(scope);}
        @Override public void checkpoint(){
            RetainedGraph.measure(scope);
            var pending=new ArrayDeque<Object>();var seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var visitor=new RetainedGraph.Visitor(){
                @Override public void reference(Object value){if(value!=null)pending.addLast(value);}
                @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
            };
            visitor.reference(scope);
            while(!pending.isEmpty()){
                var value=pending.removeFirst();if(!seen.add(value))continue;
                if(value instanceof ArrayList<?> list && list.size()==2){
                    if(list.stream().allMatch(FunctionExpr.class::isInstance))functionArguments=true;
                    if(list.stream().allMatch(ExactRational.class::isInstance))termContributions=true;
                }
                if(abortAtContributions && termContributions)throw new BucketLimit();
                if(value instanceof Map<?,?> map && map.keySet().equals(Set.of("f(x)","f(y)")))factorBuckets=true;
                if(value instanceof BinaryExpr binary && value!=source && binary.operator()==BinaryOperator.POW
                        && binary.right().equals(new NumberExpr(0)))discardedPower=true;
                if(value instanceof RetainedGraph.View view)view.retainedReferences(visitor);
                else if(value instanceof Map<?,?> map)map.forEach((key,item)->{visitor.reference(key);visitor.reference(item);});
                else if(value instanceof Collection<?> collection)collection.forEach(visitor::reference);
                else if(value instanceof Object[] array)for(var item:array)visitor.reference(item);
                else if(value instanceof Optional<?> optional)optional.ifPresent(visitor::reference);
                else if(value instanceof BinaryExpr binary){visitor.reference(binary.left());visitor.reference(binary.right());}
                else if(value instanceof FunctionExpr function)visitor.reference(function.arguments());
            }
        }
    }
    private static Expr call(String name,Expr argument){return new FunctionExpr(name,List.of(argument));}
    private static Expr plusZero(String name){return new BinaryExpr(new VariableExpr(name),BinaryOperator.ADD,new NumberExpr(0));}
    private static void released(Observation observation){
        assertTrue(observation.work>4);assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
    @Test void canonicalFunctionRetainsItsActualNormalizedArgumentList(){
        var source=new FunctionExpr("f",List.of(call("g",plusZero("x")),call("h",plusZero("y"))));
        var expected=new FunctionExpr("f",List.of(call("g",new VariableExpr("x")),call("h",new VariableExpr("y"))));
        var observation=new Observation();var canonicalizer=new ExpressionCanonicalizer();
        try(var scope=RetainedOperation.open(observation)){observation.scope=scope;assertEquals(expected,canonicalizer.canonicalize(source));}
        assertTrue(observation.functionArguments,"the actual accumulated g(x),h(y) argument list must remain owned");released(observation);
    }
    @Test void canonicalAdditionRetainsActualCoefficientContributions(){
        var term=call("f",new VariableExpr("x"));var source=new BinaryExpr(term,BinaryOperator.ADD,term);
        var expected=new BinaryExpr(new NumberExpr(2),BinaryOperator.MUL,term);
        var observation=new Observation();var canonicalizer=new ExpressionCanonicalizer();
        try(var scope=RetainedOperation.open(observation)){observation.scope=scope;assertEquals(expected,canonicalizer.canonicalize(source));}
        assertTrue(observation.termContributions,"the bucket owns both real coefficient contributions before rendering");released(observation);
    }
    @Test void canonicalMultiplicationRetainsItsActualFactorBuckets(){
        var source=new BinaryExpr(call("f",new VariableExpr("x")),BinaryOperator.MUL,call("f",new VariableExpr("y")));
        var observation=new Observation();var canonicalizer=new ExpressionCanonicalizer();
        try(var scope=RetainedOperation.open(observation)){observation.scope=scope;assertEquals(source,canonicalizer.canonicalize(source));}
        assertTrue(observation.factorBuckets,"both actual formatted keys and their owned factor buckets must overlap");released(observation);
    }
    @Test void assumptionAwarePowerExposesTheRebuiltTreeDiscardedByReduction(){
        var source=new BinaryExpr(new VariableExpr("x"),BinaryOperator.POW,new NumberExpr(0));
        var context=new AssumptionContext();var observation=new Observation();observation.source=source;
        var canonicalizer=new ExpressionCanonicalizer();
        try(var scope=RetainedOperation.open(observation)){observation.scope=scope;assertEquals(new NumberExpr(1),canonicalizer.canonicalize(source,context));}
        assertFalse(context.isEmpty());
        assertTrue(observation.discardedPower,"the freshly rebuilt x^0 exists before its valid assumption-aware reduction");released(observation);
    }
    private static final class BucketLimit extends RuntimeException {}
    @Test void bucketCheckpointAbortKeepsPaidWorkAndReleasesItsActualContributions(){
        var term=call("f",new VariableExpr("x"));var source=new BinaryExpr(term,BinaryOperator.ADD,term);
        var observation=new Observation();observation.abortAtContributions=true;
        var canonicalizer=new ExpressionCanonicalizer();
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;assertThrows(BucketLimit.class,()->canonicalizer.canonicalize(source));
        }
        assertTrue(observation.termContributions);released(observation);
        assertEquals(new BinaryExpr(new NumberExpr(2),BinaryOperator.MUL,term),canonicalizer.canonicalize(source));
    }

}
