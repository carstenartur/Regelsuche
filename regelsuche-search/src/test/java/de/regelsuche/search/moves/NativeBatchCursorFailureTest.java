package de.regelsuche.search.moves;

import static de.regelsuche.search.moves.IncrementalProviderContract.*;
import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.transform.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeBatchCursorFailureTest {
    private static final class Provider implements ExprIncrementalProvider,RetainedGraph.View {
        private final NativeMoveSearch.Primitive primitive;
        private final boolean failingClose;
        Provider(boolean failingClose){
            this.failingClose=failingClose;
            var a=PatternExpr.var("A");
            var zero=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,a,PatternExpr.num(0)),a);
            var descriptor=new MoveProvider.Descriptor("zero","zero",SearchMove.SourceKind.PRIMITIVE,SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"zero");
            primitive=new NativeMoveSearch.Primitive(descriptor,new AstRewriteTransport(List.of(zero),64,128));
        }
        @Override public MoveProvider.Descriptor descriptor(){return primitive.descriptor();}
        @Override public Definition contractDefinition(){return new Definition(NATIVE_REVISION,"zero",Kind.REGISTERED_SCHEMA,"zero","zero",Transport.NATIVE_EXPR_V1,Mathematics.PRIMITIVE,null);}
        @Override public ObjectSource<NativeMoveProof> openSource(TypedMoveSearch.State state,TypedMoveSearch.Context context,Meter meter){
            return new Source(new NativeMoveProof.Primitive(primitive.transport().generate(state.expression()).getFirst()),meter,failingClose);
        }
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(primitive);}
    }
    private static final class Source implements ObjectSource<NativeMoveProof>,RetainedGraph.View {
        private final NativeMoveProof proof;private final Meter meter;private final boolean failingClose;
        private boolean emitted;
        Source(NativeMoveProof proof,Meter meter,boolean failingClose){this.proof=proof;this.meter=meter;this.failingClose=failingClose;}
        @Override public Optional<NativeMoveProof> next(long allowance){
            if(emitted)return Optional.empty();emitted=true;meter.charge(Operation.MATCH,1);meter.charge(proof.work());return Optional.of(proof);
        }
        @Override public Status status(){return emitted?Status.EXHAUSTED:Status.READY;}
        @Override public void close(){if(failingClose)throw new IllegalStateException("close failed after a valid primitive");}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(proof);v.reference(meter);}
    }
    private static final class Depth implements TypedSourceOnlySearch.Objective,RetainedGraph.View {
        @Override public TypedSourceOnlySearch.Score evaluate(TypedMoveSearch.State state){return new TypedSourceOnlySearch.Score(state.searchDepth()==0?1:0,1);}
        @Override public void retainedReferences(RetainedGraph.Visitor v){}
    }
    /** Honest batch adapter; authority remains the separately installed real primitive verifier. */
    private record BatchWrapper(Provider delegate) implements NativeMoveProvider,RetainedGraph.View {
        @Override public MoveProvider.Descriptor descriptor(){return delegate.descriptor();}
        @Override public Batch candidates(TypedMoveSearch.State source,TypedMoveSearch.Context context){return delegate.candidates(source,context);}
        @Override public Mathematics mathematicalKind(){return Mathematics.PRIMITIVE;}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(delegate);}
    }
    private static NativeMoveSearch.Problem problem(Provider provider,MoveSearch.Scheduling scheduling,boolean sourceOnly,boolean wrapped){
        var goal=new VariableExpr("x");
        var source=new BinaryExpr(goal,BinaryOperator.ADD,new NumberExpr(0));
        return new NativeMoveSearch.Problem(source,sourceOnly?TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION):TypedMoveSearch.Context.frozen(goal),List.of(wrapped?new BatchWrapper(provider):provider),
            MoveSearch.Mode.FAST,scheduling,new MoveSearch.Budget(1,1,0,10,10000000),NativeMovePriorityPolicy.INVENTORY_ORDER,
            NativeMoveSearch.ZeroScore.INSTANCE,NativeStateValue.NONE,NativeVerifier.registered(List.of(provider.primitive)));
    }
    private static void check(MoveSearch.Scheduling scheduling){check(scheduling,false);}
    private static void check(MoveSearch.Scheduling scheduling,boolean wrapped){
        var provider=new Provider(true);var problem=problem(provider,scheduling,false,wrapped);
        var source=new TypedMoveSearch.State(problem.source(),0,0,"",List.of(),Set.of(),0);
        var move=provider.primitive.candidates(source,problem.context()).moves().getFirst();
        assertTrue(problem.verifier().verify(source,move,problem.context()).accepted(),"lifecycle failure does not invalidate mathematics");
        var result=new NativeMoveSearch().search(problem,SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE,result.observedOutcome(),"failed close must survive eager draining into a batch");
        assertFalse(result.accountingComplete());assertFalse(result.observationsComplete());assertFalse(result.withinBudget());
        assertEquals(1,result.batchCursorReceipts().size());
        var receipt=result.batchCursorReceipts().getFirst();
        assertEquals(Status.FAILED,receipt.status());assertTrue(receipt.closed());assertFalse(receipt.accountingComplete());
        assertTrue(receipt.detailCode().contains("close failed"));
        assertThrows(UnsupportedOperationException.class,()->result.batchCursorReceipts().clear());
        assertEquals(1,result.metrics().primitiveWork());assertTrue(result.totalWork()>0);
        assertEquals(scheduling!=MoveSearch.Scheduling.STAGED_INCREMENTAL,result.cursorReceipts().isEmpty(),"batch cursor receipts remain separate from actual staged-lane projections");
        var control=new NativeMoveSearch().search(problem(new Provider(false),scheduling,false,wrapped),SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(MoveSearch.Outcome.TARGET_REACHED,control.observedOutcome());assertTrue(control.observationsComplete());assertFalse(control.accountingComplete());assertFalse(control.withinBudget());assertTrue(control.totalWork()<=control.workBudget());
        var qualityProblem=problem(new Provider(true),scheduling,true,wrapped);var engine=new NativeMoveSearch();
        for(var quality:List.of(engine.searchUntil(qualityProblem,new Depth(),0,SearchContinuationContract.PATH_SENSITIVE),
                engine.searchBest(qualityProblem,new Depth(),SearchContinuationContract.PATH_SENSITIVE))){
            assertFalse(quality.search().accountingComplete());assertFalse(quality.search().observationsComplete());assertFalse(quality.withinBudget());
            assertEquals(MoveSearch.Outcome.INCONCLUSIVE,quality.search().observedOutcome());
        }
    }
    @Test void nestedBatchWrappersPreserveFailedCursorReceiptsInEveryScheduling(){
        for(var scheduling:List.of(MoveSearch.Scheduling.EAGER_CONTROL,MoveSearch.Scheduling.STAGED,MoveSearch.Scheduling.STAGED_INCREMENTAL))check(scheduling,true);
    }
    @Test void stagedBatchKeepsFailedCursorCloseReceipt(){check(MoveSearch.Scheduling.STAGED);}
    @Test void eagerBatchKeepsFailedCursorCloseReceipt(){check(MoveSearch.Scheduling.EAGER_CONTROL);}
}
