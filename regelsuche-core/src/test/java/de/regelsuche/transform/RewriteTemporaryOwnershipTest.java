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
        boolean canonicalThree,replacedArguments,emptyMutableBatch;
        List<Expr> abortAtCopiedArguments;List<?> abortedArguments;long previousCheckpointWork,copyCheckpointWork;
        Expr abortAtResult,replacedArgument;boolean batchAborted;final BatchLimit primary=new BatchLimit();
        boolean repeatPrimaryOnCleanup,repeatedCleanupInjected;int precedingCleanupFrames;
        boolean failQueueDebit,queueAndSlotAtFailedDebit;
        String failAllocationIn;int allocationUnits;boolean allocationDebitFailed,allocationAtFailedDebit;
        final List<CleanupLimit> cleanupFailures=new ArrayList<>();
        final Set<RetainedOperation.Frame> frames=Collections.newSetFromMap(new IdentityHashMap<>());
        @Override public void executionWork(long units){
            execution=Math.addExact(execution,units);
            if(failQueueDebit && units==2){failQueueDebit=false;throw primary;}
            if(failAllocationIn!=null && units==allocationUnits && completedAllocationDebit()){
                failAllocationIn=null;allocationDebitFailed=true;throw primary;
            }
            if(batchAborted && units==4){if(repeatPrimaryOnCleanup){
                    if(precedingCleanupFrames-- > 0)return;
                    repeatPrimaryOnCleanup=false;repeatedCleanupInjected=true;throw primary;
                }
                if(repeatedCleanupInjected)return;
                var cleanup=new CleanupLimit();cleanupFailures.add(cleanup);throw cleanup;}
        }
        @Override public void validationWork(long units){validation=Math.addExact(validation,units);}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(scope);}
        /** Paid equality can use the same unit count before this completed-allocation boundary. */
        private boolean completedAllocationDebit(){
            var stack=Thread.currentThread().getStackTrace();
            for(int i=0;i+1<stack.length;i++){
                var frame=stack[i];var caller=stack[i+1];
                if(frame.getClassName().equals(RetainedOperation.class.getName()) && frame.getMethodName().equals("retainCompleted")
                        && caller.getClassName().equals(PreparedAstRewriteTransformationEngine.class.getName())
                        && failAllocationIn.equals(caller.getMethodName()))return true;
            }
            return false;
        }
        @Override public void checkpoint(){
            checkpoints++;
            RetainedGraph.measure(scope);
            var pending=new ArrayDeque<Object>();var seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var expressions=new ArrayList<FunctionExpr>();var argumentLists=new ArrayList<List<?>>();int results=0;
            boolean actualQueue=false,actualSlot=false;
            boolean completedTarget=false;
            var visitor=new RetainedGraph.Visitor(){
                @Override public void reference(Object value){if(value!=null)pending.addLast(value);}
                @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
            };
            visitor.reference(scope);
            while(!pending.isEmpty()){
                var value=pending.removeFirst();if(!seen.add(value))continue;
                if(value instanceof RetainedOperation.Frame frame){
                    frames.add(frame);
                    var refs=new ArrayList<Object>();frame.retainedReferences(new RetainedGraph.Visitor(){
                        @Override public void reference(Object item){refs.add(item);}
                        @Override public void requireExact(Object item,Class<?> type){assertEquals(type,item.getClass());}
                    });
                    if(allocationDebitFailed && refs.get(2) instanceof Object[] held && held.length==2
                            && held[0] instanceof ArrayList<?> list && list.isEmpty()
                            && (held[1] instanceof Expr || held[1] instanceof List<?>))allocationAtFailedDebit=true;
                }
                if(value instanceof ArrayDeque<?>)actualQueue=true;
                if(value instanceof Object[] array && array.getClass().getComponentType().getSimpleName().equals("Node"))actualSlot=true;
                if(value instanceof ArrayList<?> list && list.isEmpty())emptyMutableBatch=true;
                if(value instanceof NumberExpr number && number.value().equalsInteger(3))canonicalThree=true;
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
                    if(direct.stream().anyMatch(RewriteRule.class::isInstance) && direct.stream().anyMatch(Expr.class::isInstance)){
                        results++;
                        if(abortAtResult!=null && direct.stream().anyMatch(this::hasTargetReferences))completedTarget=true;
                    }
                }else if(value instanceof Collection<?> collection)collection.forEach(visitor::reference);
                else if(value instanceof Map<?,?> map)map.forEach((key,item)->{visitor.reference(key);visitor.reference(item);});
                else if(value instanceof Object[] array)for(var item:array)visitor.reference(item);
            }
            if(actualQueue && actualSlot)queueAndSlotAtFailedDebit=true;
            if(completedTarget){batchAborted=true;throw primary;}
            if(abortAtCopiedArguments!=null && (allocationUnits==0 || allocationDebitFailed)
                    && argumentLists.stream().anyMatch(args->sameReferences(args,abortAtCopiedArguments))) {
                abortedArguments=argumentLists.stream().filter(args->sameReferences(args,abortAtCopiedArguments)).findFirst().orElseThrow();
                copyCheckpointWork=execution-previousCheckpointWork;
                if(allocationUnits==0)throw new CopyLimit();
            }
            previousCheckpointWork=execution;
            simultaneousResults=Math.max(simultaneousResults,results);
            for(var function:expressions)for(var args:argumentLists)
                if(args!=function.arguments() && sameReferences(args,function.arguments()) && args.getFirst()==replacedArgument)replacedArguments=true;
        }
        /** The fixture's completed ancestor must reuse its exact known child references. */
        private boolean hasTargetReferences(Object value){
            if(value==abortAtResult)return true;
            if(value instanceof BinaryExpr actual && abortAtResult instanceof BinaryExpr expected)
                return actual.operator()==expected.operator() && actual.left()==expected.left() && actual.right()==expected.right();
            if(value instanceof FunctionExpr actual && abortAtResult instanceof FunctionExpr expected)
                return actual.name().equals(expected.name()) && sameReferences(actual.arguments(),expected.arguments());
            return false;
        }
    }
    /** Ownership probes must not invoke the paid Expr value-comparison path. */
    private static boolean sameReferences(List<?> first,List<?> second){
        if(first.size()!=second.size())return false;
        for(int i=0;i<first.size();i++)if(first.get(i)!=second.get(i))return false;
        return true;
    }
    @Test void boundsTraversalPaysAndExposesItsActualPendingQueue(){
        var root=new FunctionExpr("f",List.of(new VariableExpr("a"),new VariableExpr("b"),new VariableExpr("c")));
        var observation=new Observation();
        try(var scope=RetainedOperation.open(observation)){observation.scope=scope;AstRewriteTransport.requireBounded(root);}
        assertAll(()->assertEquals(4,observation.validation,"each actual visited AST occurrence pays validation"),
            ()->assertEquals(3,observation.queueWidth,"all three actual pending children must be retained simultaneously"));
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes(),"closed traversal releases its AST queue");
    }
    @Test void failedInitialBoundedTraversalDebitStillObservesQueueAndSlot(){
        var observation=new Observation();observation.failQueueDebit=true;
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;
            assertSame(observation.primary,assertThrows(BatchLimit.class,
                ()->AstRewriteTransport.requireBounded(new VariableExpr("x"))));
            assertTrue(observation.queueAndSlotAtFailedDebit,"the allocated queue and slot overlap at the failed debit");
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
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
        var observation=new Observation();observation.replacedArgument=((BinaryExpr)root.arguments().getFirst()).left();
        List<AstRewriteTransport.Step> actual;
        try(var scope=RetainedOperation.open(observation)){observation.scope=scope;actual=transport.generate(root);}
        assertEquals(expected,actual);
        assertAll(()->assertTrue(observation.simultaneousResults>=2,"child result and rebuilt ancestor result are live together"),
            ()->assertTrue(observation.replacedArguments,"mutable rebuilt argument list overlaps FunctionExpr's immutable argument list"));
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
    private static final class CopyLimit extends RuntimeException {}
    private static final class BatchLimit extends RuntimeException {}
    private static final class CleanupLimit extends RuntimeException {}
    private static void assertOriginalFailureSurvivesCleanup(Expr source,Expr target){
        assertOriginalFailureSurvivesCleanup(source,target,false);
    }
    private static void assertOriginalFailureSurvivesCleanup(Expr source,Expr target,boolean sameFailure){
        var a=PatternExpr.var("A");
        var zero=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,a,PatternExpr.num(0)),a);
        var transport=new AstRewriteTransport(List.of(zero),32,32);
        var expected=transport.generate(source);assertEquals(1,expected.size());assertEquals(target,expected.getFirst().target());
        var observation=new Observation();observation.abortAtResult=target;observation.repeatPrimaryOnCleanup=sameFailure;
        observation.precedingCleanupFrames=target instanceof FunctionExpr ? 2 : target instanceof BinaryExpr ? 1 : 0;
        RuntimeException thrown;
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;thrown=assertThrows(RuntimeException.class,()->transport.generate(source));
        }
        assertTrue(observation.batchAborted,"the real filled candidate batch reached the failing checkpoint");
        if(!sameFailure)assertFalse(observation.cleanupFailures.isEmpty(),"actual frame release also failed after releasing ownership");
        assertEquals(new RetainedGraph.Usage(0,0,4),RetainedGraph.measure(observation.scope).retained());
        for(var frame:observation.frames)
            assertEquals(new RetainedGraph.Usage(0,0,4),RetainedGraph.measure(frame).retained());
        assertEquals(expected,transport.generate(source),"the aborted observation does not leak into historical calls");
        assertSame(observation.primary,thrown,"cleanup must not replace the original resource failure");
        assertEquals(observation.cleanupFailures,List.of(thrown.getSuppressed()));
    }
    @Test void directRewritePreservesPrimaryFailureWhenReleaseAlsoFails(){
        var x=new VariableExpr("x");
        assertOriginalFailureSurvivesCleanup(new BinaryExpr(x,BinaryOperator.ADD,new NumberExpr(0)),x);
    }
    @Test void binaryChildRewritePreservesPrimaryFailureWhenReleaseAlsoFails(){
        var x=new VariableExpr("x");var y=new VariableExpr("y");
        var child=new BinaryExpr(x,BinaryOperator.ADD,new NumberExpr(0));
        assertOriginalFailureSurvivesCleanup(new BinaryExpr(child,BinaryOperator.MUL,y),new BinaryExpr(x,BinaryOperator.MUL,y));
    }
    @Test void functionRewritePreservesPrimaryFailureWhenReleaseAlsoFails(){
        var x=new VariableExpr("x");var y=new VariableExpr("y");
        var child=new BinaryExpr(x,BinaryOperator.ADD,new NumberExpr(0));
        assertOriginalFailureSurvivesCleanup(new FunctionExpr("f",List.of(child,y)),new FunctionExpr("f",List.of(x,y)));
    }
    @Test void repeatedPrimaryFailureDuringDirectReleaseIsNotSelfSuppressed(){
        var x=new VariableExpr("x");
        assertOriginalFailureSurvivesCleanup(new BinaryExpr(x,BinaryOperator.ADD,new NumberExpr(0)),x,true);
    }
    @Test void repeatedPrimaryFailureDuringBinaryReleaseIsNotSelfSuppressed(){
        var x=new VariableExpr("x");var y=new VariableExpr("y");
        assertOriginalFailureSurvivesCleanup(new BinaryExpr(new BinaryExpr(x,BinaryOperator.ADD,new NumberExpr(0)),BinaryOperator.MUL,y),
            new BinaryExpr(x,BinaryOperator.MUL,y),true);
    }
    @Test void repeatedPrimaryFailureDuringFunctionReleaseIsNotSelfSuppressed(){
        var x=new VariableExpr("x");var y=new VariableExpr("y");
        assertOriginalFailureSurvivesCleanup(new FunctionExpr("f",List.of(new BinaryExpr(x,BinaryOperator.ADD,new NumberExpr(0)),y)),
            new FunctionExpr("f",List.of(x,y)),true);
    }
    private static void assertFailedResultAllocationDebit(String method,Expr source){
        var a=PatternExpr.var("A");
        var zero=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,a,PatternExpr.num(0)),a);
        var transport=new AstRewriteTransport(List.of(zero),32,32);
        var observation=new Observation();observation.failAllocationIn=method;observation.allocationUnits=1;
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;
            assertSame(observation.primary,assertThrows(BatchLimit.class,()->transport.generate(source)));
            assertTrue(observation.allocationAtFailedDebit,"the newly allocated result list must be owned when its debit fails");
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
        assertEquals(1,transport.generate(source).size());
    }
    @Test void failedDirectResultListDebitObservesItsActualAllocation(){
        assertFailedResultAllocationDebit("rewriteEverywhere",
            new BinaryExpr(new VariableExpr("x"),BinaryOperator.ADD,new NumberExpr(0)));
    }
    @Test void failedBinaryChildResultListDebitObservesItsActualAllocation(){
        assertFailedResultAllocationDebit("rewriteBinaryChildren",
            new BinaryExpr(new VariableExpr("y"),BinaryOperator.MUL,
                new BinaryExpr(new VariableExpr("x"),BinaryOperator.ADD,new NumberExpr(0))));
    }
    @Test void failedFunctionChildResultListDebitObservesItsActualAllocation(){
        assertFailedResultAllocationDebit("rewriteFunctionArguments",
            new FunctionExpr("f",List.of(new BinaryExpr(new VariableExpr("x"),BinaryOperator.ADD,new NumberExpr(0)))));
    }
    @Test void failedArgumentCopyDebitObservesItsActualMutableList(){
        var a=PatternExpr.var("A");var zero=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,a,PatternExpr.num(0)),a);
        var arguments=List.<Expr>of(new BinaryExpr(new VariableExpr("x"),BinaryOperator.ADD,new NumberExpr(0)),new VariableExpr("y"),new VariableExpr("z"));
        var observation=new Observation();observation.failAllocationIn="appendFunctionRewrite";observation.allocationUnits=arguments.size();
        observation.abortAtCopiedArguments=arguments;
        var transport=new AstRewriteTransport(List.of(zero),32,32);var source=new FunctionExpr("f",arguments);
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;
            assertSame(observation.primary,assertThrows(BatchLimit.class,()->transport.generate(source)));
        }
        assertEquals(arguments,observation.abortedArguments,"the copied argument list remains observable at the failed debit");
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }
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

    @Test void emptyNativeRewriteDoesNotAllocateAMutableCandidateBatch(){
        var source=new VariableExpr("x");var observation=new Observation();
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;assertTrue(new AstRewriteTransport(List.of(),32,32).generate(source).isEmpty());
        }
        assertFalse(observation.emptyMutableBatch,"a no-candidate leaf returns the shared empty batch without allocating an ArrayList");
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

}
