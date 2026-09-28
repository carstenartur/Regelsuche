package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RewriteTemporaryOwnershipTest {
    /** Walks only audited references, never private fields or a second rewrite implementation. */
    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;long execution,validation;int queueWidth,simultaneousResults,checkpoints;
        boolean canonicalThree,replacedArguments;
        List<Expr> abortAtCopiedArguments;List<?> abortedArguments;long previousCheckpointWork,copyCheckpointWork;
        @Override public void executionWork(long units){execution=Math.addExact(execution,units);}
        @Override public void validationWork(long units){validation=Math.addExact(validation,units);}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(scope);}
        @Override public void checkpoint(){
            checkpoints++;
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
            if(abortAtCopiedArguments!=null && argumentLists.stream().anyMatch(abortAtCopiedArguments::equals)) {
                abortedArguments=argumentLists.stream().filter(abortAtCopiedArguments::equals).findFirst().orElseThrow();
                copyCheckpointWork=execution-previousCheckpointWork;
                throw new CopyLimit();
            }
            previousCheckpointWork=execution;
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
    private static final class CopyLimit extends RuntimeException {}
    @Test void argumentCopyIsPaidBeforeItsOwnershipCheckpointCanAbort(){
        var a=PatternExpr.var("A");
        var zero=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,a,PatternExpr.num(0)),a);
        var arguments=List.<Expr>of(new BinaryExpr(new VariableExpr("x"),BinaryOperator.ADD,new NumberExpr(0)),
            new VariableExpr("y"),new VariableExpr("z"));
        var source=new FunctionExpr("f",arguments);
        var transport=new AstRewriteTransport(List.of(zero),32,32);
        var observation=new Observation();observation.abortAtCopiedArguments=arguments;
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;
            assertThrows(CopyLimit.class,()->transport.generate(source));
        }
        assertEquals(arguments.size()+2,observation.copyCheckpointWork,
            "completed argument copies and frame acquisition are paid before the failing checkpoint");
        assertEquals(arguments,observation.abortedArguments,"the actual copied arguments remain unchanged when their checkpoint aborts");
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
        assertEquals(1,transport.generate(source).size(),"the failed scope cannot leak into a later historical call");
    }

    @Test void boundedTraversalScansOnlyWhenItsOwnedQueueGraphCanGrow(){
        var leaf=new VariableExpr("x");
        var root=new FunctionExpr("f",List.of(leaf,leaf,leaf));
        var observation=new Observation();
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;AstRewriteTransport.requireBounded(root);
        }
        assertEquals(4,observation.validation,"all four occurrences are inspected despite shared identity");
        assertEquals(3,observation.queueWidth,"actual simultaneous pending children stay observable");
        assertEquals(3,observation.checkpoints,"initial frame, initial root Node and actual child expansion; leaf visits do not grow ownership");
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

}
