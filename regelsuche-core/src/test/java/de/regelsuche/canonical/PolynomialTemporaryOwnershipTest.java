package de.regelsuche.canonical;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import de.regelsuche.scalar.ExactRational;
import java.util.*;
import org.junit.jupiter.api.Test;

class PolynomialTemporaryOwnershipTest {
    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;long work;int simultaneousTerms;boolean rejectedCoefficient;
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
            visitor.reference(scope);int terms=0;
            while(!pending.isEmpty()){
                var value=pending.removeFirst();if(!seen.add(value))continue;
                if(value instanceof ExactRational rational && rational.numerator().bitLength()>4096)rejectedCoefficient=true;
                if(value instanceof RetainedGraph.View view)view.retainedReferences(visitor);
                else if(value instanceof Map<?,?> map){
                    if(!map.isEmpty() && map.values().stream().allMatch(ExactRational.class::isInstance))terms++;
                    map.forEach((key,item)->{visitor.reference(key);visitor.reference(item);});
                }else if(value instanceof Collection<?> collection)collection.forEach(visitor::reference);
                else if(value instanceof Object[] array)for(var item:array)visitor.reference(item);
                else if(value instanceof NumberExpr number)visitor.reference(number.value());
                else if(value instanceof BinaryExpr binary){visitor.reference(binary.left());visitor.reference(binary.right());}
            }
            simultaneousTerms=Math.max(simultaneousTerms,terms);
        }
    }
    @Test void realPolynomialMultiplicationRetainsBothOperandsAndAccumulatingTerms(){
        var sum=new BinaryExpr(new VariableExpr("x"),BinaryOperator.ADD,new VariableExpr("y"));
        var normalizer=new PolynomialNormalizer();
        for(var expression:List.of(new BinaryExpr(sum,BinaryOperator.MUL,sum),new BinaryExpr(sum,BinaryOperator.POW,new NumberExpr(3)))){
            var expected=normalizer.normalize(expression);assertTrue(expected.isPresent());
            var observation=new Observation();
            try(var scope=RetainedOperation.open(observation)){observation.scope=scope;assertEquals(expected,normalizer.normalize(expression));}
            assertTrue(observation.simultaneousTerms>=3,"left/right polynomial terms and the actual accumulating map overlap");
            assertTrue(observation.work>0);assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
        }
    }
    @Test void rejectedCoefficientIsObservedAndPaidBeforeItsIntermediatePolynomialIsDiscarded(){
        var expression=new BinaryExpr(new NumberExpr(2),BinaryOperator.POW,new NumberExpr(4096));
        var normalizer=new PolynomialNormalizer();assertTrue(normalizer.normalize(expression).isEmpty());
        var observation=new Observation();
        try(var scope=RetainedOperation.open(observation)){observation.scope=scope;assertTrue(normalizer.normalize(expression).isEmpty());}
        assertTrue(observation.rejectedCoefficient,"the actual 4097-bit coefficient exists before the unchanged 4096-bit rejection");
        assertTrue(observation.work>4,"attempted normalization does more than opening/closing the outer scope");
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
}
