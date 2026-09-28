package de.regelsuche.search.moves;

import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedGraph;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeRetentionSearchTest {
    private enum AllocatingScore implements java.util.function.ToDoubleFunction<TypedMoveSearch.State>,RetainedGraph.View {
        INSTANCE;
        private final java.util.ArrayList<de.regelsuche.ast.Expr> cache=new java.util.ArrayList<>();
        @Override public double applyAsDouble(TypedMoveSearch.State state){
            for(int i=0;i<100;i++)cache.add(new VariableExpr("temporary"+i));
            return 0;
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(cache);}
    }
    private static final class FinalAllocation implements NativeVerifier,RetainedGraph.View {
        private final NativeVerifier verifier;private int calls;
        FinalAllocation(NativeVerifier verifier){this.verifier=verifier;}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(verifier);}
        @Override public NativeVerification verify(TypedMoveSearch.State source,NativeSearchMove move,TypedMoveSearch.Context context){
            if(++calls==2) {
                var temporary=new java.util.ArrayList<de.regelsuche.ast.Expr>();
                for(int i=0;i<20;i++)temporary.add(new VariableExpr("final"+i));
                try(var retained=de.regelsuche.retention.RetainedOperation.retain(temporary)) {return verifier.verify(source,move,context);}
            }
            return verifier.verify(source,move,context);
        }
    }
    @Test void finalIndependentReplayReturnsItsPaidResourceFailureInsteadOfLosingTheResult() {
        var a=de.regelsuche.transform.PatternExpr.var("A");
        var rule=new de.regelsuche.transform.PatternRewriteRule("zero",de.regelsuche.transform.PatternExpr.op(
            de.regelsuche.ast.BinaryOperator.ADD,a,de.regelsuche.transform.PatternExpr.num(0)),a);
        var target=new VariableExpr("x");
        var source=new de.regelsuche.ast.BinaryExpr(target,de.regelsuche.ast.BinaryOperator.ADD,new de.regelsuche.ast.NumberExpr(0));
        var descriptor=new MoveProvider.Descriptor("zero","zero",SearchMove.SourceKind.PRIMITIVE,SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"zero-v1");
        var provider=new NativeMoveSearch.Primitive(descriptor,new de.regelsuche.transform.AstRewriteTransport(List.of(rule),64,128));
        var verifier=new FinalAllocation(NativeVerifier.registered(List.of(provider)));
        var problem=new NativeMoveSearch.Problem(source,TypedMoveSearch.Context.frozen(target),List.of(provider),MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(1,1,0,10,1000000),NativeMovePriorityPolicy.INVENTORY_ORDER,NativeMoveSearch.ZeroScore.INSTANCE,NativeStateValue.NONE,verifier);
        var result=assertDoesNotThrow(()->new NativeMoveSearch().search(problem,SearchContinuationContract.PATH_SENSITIVE,
            new SearchExpressionStore.Limits(10,1000000,1000000,0)));
        assertEquals(2,verifier.calls);assertEquals(1,result.witness().size(),"the admitted witness must survive failed final qualification");
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE,result.outcome());assertFalse(result.accountingComplete());assertFalse(result.withinBudget());
        assertEquals("NATIVE_RETENTION_EXHAUSTED",result.accounting().detail());
        assertTrue(result.accounting().peak().nodes()>=20);assertTrue(result.totalWork()>result.metrics().totalWork());
        assertEquals(new RetainedGraph.Usage(0,0,0),result.accounting().live());
    }

    @Test void anEnumCallbackCannotHideTheRunCreatedGraphDeclaredByItsView() {
        var a=de.regelsuche.transform.PatternExpr.var("A");
        var rule=new de.regelsuche.transform.PatternRewriteRule("zero",de.regelsuche.transform.PatternExpr.op(
            de.regelsuche.ast.BinaryOperator.ADD,a,de.regelsuche.transform.PatternExpr.num(0)),a);
        var target=new VariableExpr("x");
        var source=new de.regelsuche.ast.BinaryExpr(new de.regelsuche.ast.BinaryExpr(target,de.regelsuche.ast.BinaryOperator.ADD,new de.regelsuche.ast.NumberExpr(0)),
            de.regelsuche.ast.BinaryOperator.ADD,new de.regelsuche.ast.NumberExpr(0));
        var descriptor=new MoveProvider.Descriptor("zero","zero",SearchMove.SourceKind.PRIMITIVE,SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"zero-v1");
        var provider=new NativeMoveSearch.Primitive(descriptor,new de.regelsuche.transform.AstRewriteTransport(List.of(rule),64,128));
        try {
            var problem=new NativeMoveSearch.Problem(source,TypedMoveSearch.Context.frozen(target),List.of(provider),MoveSearch.Mode.FAST,
                MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(2,2,0,10,1000000),NativeMovePriorityPolicy.INVENTORY_ORDER,AllocatingScore.INSTANCE,NativeStateValue.NONE);
            var result=new NativeMoveSearch().search(problem,SearchContinuationContract.PATH_SENSITIVE,new SearchExpressionStore.Limits(20,1000000,1000000,0));
            assertEquals(MoveSearch.Outcome.INCONCLUSIVE,result.outcome());assertFalse(result.accountingComplete());
            assertEquals("NATIVE_RETENTION_EXHAUSTED",result.accounting().detail());
            assertTrue(result.accounting().peak().nodes()>=100);
        } finally {AllocatingScore.INSTANCE.cache.clear();}
    }

    @Test void sharedInputTextCannotTurnTheStoreBoundaryIntoAnUnreceiptedException() {
        String shared="x".repeat(100);
        var source=new de.regelsuche.ast.FunctionExpr("f",List.of(new VariableExpr(shared),new VariableExpr(shared)));
        var problem=new NativeMoveSearch.Problem(source,TypedMoveSearch.Context.frozen(source),List.of(),MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(1,1,0,10,1000000));
        var result=assertDoesNotThrow(()->new NativeMoveSearch().search(problem,SearchContinuationContract.PATH_SENSITIVE,
            new SearchExpressionStore.Limits(10,150,10000,1)));
        assertEquals(MoveSearch.Outcome.TARGET_REACHED,result.outcome(),result.accounting().detail());
        assertTrue(result.accountingComplete());assertTrue(result.withinBudget());
        var rejected=assertDoesNotThrow(()->new NativeMoveSearch().search(problem,SearchContinuationContract.PATH_SENSITIVE,
            new SearchExpressionStore.Limits(2,150,10000,1)));
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE,rejected.outcome());
        assertTrue(rejected.totalWork()>0);assertEquals("NATIVE_RETENTION_EXHAUSTED",rejected.accounting().detail());
    }

    @Test void discardedAtomicRewriteStillConsumesRetentionAndWork() {
        var arguments=new de.regelsuche.transform.PatternExpr[20];
        java.util.Arrays.fill(arguments,de.regelsuche.transform.PatternExpr.num(1));
        var rule=new de.regelsuche.transform.PatternRewriteRule("large",de.regelsuche.transform.PatternExpr.var("A"),
            de.regelsuche.transform.PatternExpr.fn("large",arguments));
        var descriptor=new MoveProvider.Descriptor("large","large",SearchMove.SourceKind.PRIMITIVE,SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"large-v1");
        var provider=new NativeMoveSearch.Primitive(descriptor,new de.regelsuche.transform.AstRewriteTransport(List.of(rule),0,128));
        var problem=new NativeMoveSearch.Problem(new VariableExpr("x"),TypedMoveSearch.Context.frozen(new VariableExpr("y")),List.of(provider),
            MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(2,1,0,10,1000000));
        var result=new NativeMoveSearch().search(problem,SearchContinuationContract.PATH_SENSITIVE,new SearchExpressionStore.Limits(10,1000000,1000000,0));
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE,result.outcome(),"the generated AST is rejected by growth before any batch is emitted, but still occupies memory");
        assertEquals("NATIVE_RETENTION_EXHAUSTED",result.accounting().detail());
        assertTrue(result.accounting().peak().nodes()>10);assertTrue(result.totalWork()>0);
        assertTrue(result.witness().isEmpty());
    }

    @Test void registeredPrimitivePathOwnsItsProvidersPickerProposalsAndCheckedResult() {
        var a=de.regelsuche.transform.PatternExpr.var("A");
        var rule=new de.regelsuche.transform.PatternRewriteRule("zero",de.regelsuche.transform.PatternExpr.op(
            de.regelsuche.ast.BinaryOperator.ADD,a,de.regelsuche.transform.PatternExpr.num(0)),a);
        var target=new VariableExpr("x");
        var source=new de.regelsuche.ast.BinaryExpr(target,de.regelsuche.ast.BinaryOperator.ADD,new de.regelsuche.ast.NumberExpr(0));
        var descriptor=new MoveProvider.Descriptor("zero","zero",SearchMove.SourceKind.PRIMITIVE,SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"zero-v1");
        var provider=new NativeMoveSearch.Primitive(descriptor,new de.regelsuche.transform.AstRewriteTransport(List.of(rule),64,128));
        for(var scheduling:List.of(MoveSearch.Scheduling.STAGED,MoveSearch.Scheduling.EAGER_CONTROL,MoveSearch.Scheduling.STAGED_INCREMENTAL)) {
            var problem=new NativeMoveSearch.Problem(source,TypedMoveSearch.Context.frozen(target),List.of(provider),
                MoveSearch.Mode.FAST,scheduling,new MoveSearch.Budget(2,1,0,10,1000000));
            var result=new NativeMoveSearch().search(problem,SearchContinuationContract.PATH_SENSITIVE,SearchExpressionStore.Limits.DEFAULT);
            assertEquals(MoveSearch.Outcome.TARGET_REACHED,result.outcome(),result.accounting().detail());
            assertTrue(result.accountingComplete());assertTrue(result.withinBudget());
            assertSame(target,result.output());assertEquals(1,result.witness().size());
            assertTrue(result.accounting().resultRetained().nodes()>=3);
            assertTrue(result.accounting().peak().references()>result.accounting().resultRetained().references());
            assertTrue(result.replayWork()>0);
        }
    }

    @Test void paidOwnershipWorkParticipatesInTheExistingBudgetAndOpaqueCallbacksFailClosed() {
        var expression=new VariableExpr("x");
        var small=new NativeMoveSearch.Problem(expression,TypedMoveSearch.Context.frozen(expression),List.of(),
            MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(1,1,0,10,1));
        var overrun=new NativeMoveSearch().search(small,SearchContinuationContract.PATH_SENSITIVE,SearchExpressionStore.Limits.DEFAULT);
        assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED,overrun.outcome());assertFalse(overrun.withinBudget());
        assertTrue(overrun.accounting().retentionWork()>1);
        var opaque=new NativeMoveSearch.Problem(expression,small.context(),List.of(),small.mode(),small.scheduling(),
            new MoveSearch.Budget(1,1,0,10,100000),NativeMovePriorityPolicy.INVENTORY_ORDER,s->0,NativeStateValue.NONE);
        var unknown=new NativeMoveSearch().search(opaque,SearchContinuationContract.PATH_SENSITIVE,SearchExpressionStore.Limits.DEFAULT);
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE,unknown.outcome());assertFalse(unknown.accountingComplete());
        assertTrue(unknown.accounting().detail().startsWith("NATIVE_RETENTION_UNSUPPORTED:"));
        assertTrue(unknown.accounting().retentionWork()>0);
    }
    @Test void totalRunLimitIncludesTheKernelAndItsAccountingAndFailureRetainsPaidWork() {
        var expression=new VariableExpr("x");
        var problem=new NativeMoveSearch.Problem(expression,TypedMoveSearch.Context.frozen(expression),List.of(),
            MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(1,1,0,10,100000));
        var rejected=new NativeMoveSearch().search(problem,SearchContinuationContract.PATH_SENSITIVE,
            new SearchExpressionStore.Limits(1,2,4,0));
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE,rejected.outcome());
        assertEquals("NATIVE_RETENTION_EXHAUSTED",rejected.accounting().detail());
        assertFalse(rejected.accountingComplete());assertFalse(rejected.withinBudget());
        assertTrue(rejected.accounting().retentionWork()>0);assertTrue(rejected.totalWork()>0);
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE,rejected.exportLegacy().outcome());
        var accepted=new NativeMoveSearch().search(problem,SearchContinuationContract.PATH_SENSITIVE,SearchExpressionStore.Limits.DEFAULT);
        assertEquals(MoveSearch.Outcome.TARGET_REACHED,accepted.outcome());assertTrue(accepted.withinBudget());
        assertTrue(accepted.accounting().validationWork()>0);assertTrue(accepted.accounting().storageWork()>0);
        assertTrue(accepted.accounting().retentionWork()>0);
        assertEquals(new RetainedGraph.Usage(0,0,0),accepted.accounting().live(),"closed session retains no lookup graph");
        assertEquals(1,accepted.accounting().resultRetained().nodes());
        assertTrue(accepted.accounting().peak().references()>accepted.accounting().resultRetained().references());
        assertSame(expression,accepted.output(),"immutable result remains usable after lookup indexes close");
    }
}
