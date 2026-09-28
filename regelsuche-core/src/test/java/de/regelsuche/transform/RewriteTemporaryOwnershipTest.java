package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RewriteTemporaryOwnershipTest {
    /** Walks only audited references, never private fields or a second rewrite implementation. */
    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;long execution,validation;int queueWidth,simultaneousResults;
        boolean canonicalThree,replacedArguments;
        @Override public void executionWork(long units){execution=Math.addExact(execution,units);}
        @Override public void validationWork(long units){validation=Math.addExact(validation,units);}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(scope);}
        @Override public void checkpoint(){
            RetainedGraph.measure(scope);
            var pending=new ArrayDeque<Object>();var seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var expressions=new ArrayList<FunctionExpr>();var argumentLists=new ArrayList<List<?>>();int results=0;
            var visitor=new RetainedGraph.Visitor(){
                @Override public void reference(Object value){if(value!=null)pending.addLast(value);}
                @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
            };
            visitor.reference(scope);
            while(!pending.isEmpty()){
                var value=pending.removeFirst();if(!seen.add(value))continue;
                if(value instanceof NumberExpr number && number.equals(new NumberExpr(3)))canonicalThree=true;
                if(value instanceof ArrayDeque<?> queue && queue.stream().allMatch(RetainedGraph.View.class::isInstance))queueWidth=Math.max(queueWidth,queue.size());
                if(value instanceof ArrayList<?> list && !list.isEmpty() && list.stream().allMatch(Expr.class::isInstance))argumentLists.add(list);
                if(value instanceof FunctionExpr function){expressions.add(function);function.arguments().forEach(visitor::reference);}
                else if(value instanceof BinaryExpr binary){visitor.reference(binary.left());visitor.reference(binary.right());}
                else if(value instanceof RetainedGraph.View view){
                    var direct=new ArrayList<Object>();
                    view.retainedReferences(new RetainedGraph.Visitor(){
                        @Override public void reference(Object item){direct.add(item);visitor.reference(item);}
                        @Override public void requireExact(Object item,Class<?> type){visitor.requireExact(item,type);}
                    });
                    if(direct.stream().anyMatch(RewriteRule.class::isInstance) && direct.stream().anyMatch(Expr.class::isInstance))results++;
                }else if(value instanceof Collection<?> collection)collection.forEach(visitor::reference);
                else if(value instanceof Map<?,?> map)map.forEach((key,item)->{visitor.reference(key);visitor.reference(item);});
                else if(value instanceof Object[] array)for(var item:array)visitor.reference(item);
            }
            simultaneousResults=Math.max(simultaneousResults,results);
            for(var function:expressions)for(var args:argumentLists)
                if(args!=function.arguments() && args.equals(function.arguments()) && args.getFirst().equals(new VariableExpr("x")))replacedArguments=true;
        }
    }
    @Test void boundsTraversalPaysAndExposesItsActualPendingQueue(){
        var root=new FunctionExpr("f",List.of(new VariableExpr("a"),new VariableExpr("b"),new VariableExpr("c")));
        var observation=new Observation();
        try(var scope=RetainedOperation.open(observation)){observation.scope=scope;AstRewriteTransport.requireBounded(root);}
        assertAll(()->assertEquals(4,observation.validation,"each actual visited AST occurrence pays validation"),
            ()->assertEquals(3,observation.queueWidth,"all three actual pending children must be retained simultaneously"));
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes(),"closed traversal releases its AST queue");
    }
    @Test void rejectedDepthKeepsAttemptedValidationAndReleasesTheQueue(){
        Expr root=new VariableExpr("x");for(int i=0;i<129;i++)root=new BinaryExpr(root,BinaryOperator.ADD,new NumberExpr(0));
        var source=root;var observation=new Observation();
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;assertThrows(IllegalArgumentException.class,()->AstRewriteTransport.requireBounded(source));
        }
        assertEquals(130,observation.validation,"the rejected depth-129 occurrence is still inspected and paid");
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
    @Test void canonicalResultDiscardedByCountingIsObservedBeforeItIsReleased(){
        var expression=new BinaryExpr(new NumberExpr(1),BinaryOperator.ADD,new NumberExpr(2));
        var engine=new PreparedAstRewriteTransformationEngine(List.of());var observation=new Observation();
        try(var scope=RetainedOperation.open(observation)){observation.scope=scope;assertEquals(1,engine.canonicalAstNodeCount(expression));}
        assertTrue(observation.canonicalThree,"the actual newly canonicalized 3 is live before the counting result discards it");
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
    @Test void recursiveChildBatchAndMutableArgumentsOverlapTheRebuiltAncestor(){
        var a=PatternExpr.var("A");var zero=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,a,PatternExpr.num(0)),a);
        var root=new FunctionExpr("f",List.of(new BinaryExpr(new VariableExpr("x"),BinaryOperator.ADD,new NumberExpr(0)),new VariableExpr("y")));
        var transport=new AstRewriteTransport(List.of(zero),32,32);var expected=transport.generate(root);assertEquals(1,expected.size());
        var observation=new Observation();
        try(var scope=RetainedOperation.open(observation)){observation.scope=scope;assertEquals(expected,transport.generate(root));}
        assertAll(()->assertTrue(observation.simultaneousResults>=2,"child result and rebuilt ancestor result are live together"),
            ()->assertTrue(observation.replacedArguments,"mutable rebuilt argument list overlaps FunctionExpr's immutable argument list"));
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
}
