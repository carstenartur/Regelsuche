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
        RetainedOperation scope;Expr source;long work,validation,peakNodes;Expr requiredRoot;boolean missingRoot;int inputOwners;
        boolean functionArguments,termContributions,factorBuckets,discardedPower,abortAtContributions;
        @Override public void executionWork(long units){work=Math.addExact(work,units);}
        @Override public void validationWork(long units){work=Math.addExact(work,units);validation=Math.addExact(validation,units);}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(scope);}
        @Override public void checkpoint(){
            peakNodes=Math.max(peakNodes,RetainedGraph.measure(scope).retained().nodes());
            var pending=new ArrayDeque<Object>();var seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var visitor=new RetainedGraph.Visitor(){
                @Override public void reference(Object value){if(value!=null)pending.addLast(value);}
                @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
            };
            visitor.reference(scope);int owners=0;
            while(!pending.isEmpty()){
                var value=pending.removeFirst();if(!seen.add(value))continue;
                if(value instanceof Object[] held && held.length==3 && held[0] instanceof ExpressionCanonicalizer && held[1] instanceof Expr)owners++;
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
            inputOwners=Math.max(inputOwners,owners);
            if(requiredRoot!=null && !seen.contains(requiredRoot))missingRoot=true;
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

    @Test void privateCanonicalRecursionReusesTheLiveImmutableInputOwner(){
        var source=new FunctionExpr("f",List.of(call("g",new VariableExpr("x")),call("h",new VariableExpr("y"))));
        var observation=new Observation();observation.requiredRoot=source;
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;assertSame(source,new ExpressionCanonicalizer().canonicalize(source));
        }
        assertFalse(observation.missingRoot,"every nested checkpoint still sees the complete input graph");
        assertEquals(5,observation.validation,"all actual recursive visits remain paid");
        assertFalse(observation.functionArguments,"unchanged arguments need no copied list");
        assertEquals(5,observation.peakNodes,"unchanged function ancestors retain their original AST identity");
        assertEquals(1,observation.inputOwners,"only the public boundary allocates the input owner");
        released(observation);
    }

    @Test void changingOneFunctionArgumentKeepsItsUnchangedSiblingsByIdentity(){
        var first=call("g",new VariableExpr("x"));var last=call("h",new VariableExpr("z"));
        var source=new FunctionExpr("f",List.of(first,plusZero("y"),last));
        var observation=new Observation();observation.requiredRoot=source;
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;
            var result=(FunctionExpr)new ExpressionCanonicalizer().canonicalize(source);
            assertNotSame(source,result);assertSame(first,result.arguments().get(0));
            assertEquals(new VariableExpr("y"),result.arguments().get(1));assertSame(last,result.arguments().get(2));
        }
        assertFalse(observation.missingRoot);released(observation);
    }

}
