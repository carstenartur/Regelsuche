package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import de.regelsuche.search.program.*;
import de.regelsuche.transform.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class NativeUnreturnedWorkTest {
    /** Injects the existing resource failure only after the real program has returned its batch. */
    private static final class AfterProgram implements RetainedOperation.Sink {
        RetainedOperation scope;
        final IdentityHashMap<NativeSearchMove,Boolean> existing=new IdentityHashMap<>();
        boolean batchSeen,initializing;
        long work;
        @Override public void executionWork(long units){work=Math.addExact(work,units);}
        @Override public void validationWork(long units){work=Math.addExact(work,units);}
        @Override public long observedWork(){return work;}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(scope);v.reference(existing);}
        void initialize(){initializing=true;checkpoint();initializing=false;batchSeen=false;}
        @Override public void checkpoint(){
            work=Math.addExact(work,RetainedGraph.measure(scope).work());
            var pending=new ArrayDeque<Object>();var seen=new IdentityHashMap<Object,Boolean>();
            var visitor=new RetainedGraph.Visitor(){
                @Override public void reference(Object value){if(value!=null)pending.add(value);}
                @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
            };
            visitor.reference(scope);boolean freshMove=false;
            while(!pending.isEmpty()){
                var value=pending.removeFirst();if(seen.put(value,Boolean.TRUE)!=null)continue;
                if(value instanceof CompiledAstRewriteProgram.Batch)batchSeen=true;
                if(value instanceof NativeSearchMove move){if(initializing)existing.put(move,true);else freshMove|=!existing.containsKey(move);}
                if(value instanceof RetainedGraph.View view)view.retainedReferences(visitor);
                else if(value instanceof Collection<?> values)values.forEach(visitor::reference);
                else if(value instanceof Map<?,?> values)values.forEach((key,item)->{visitor.reference(key);visitor.reference(item);});
                else if(value instanceof Object[] values)for(var item:values)visitor.reference(item);
            }
            if(!initializing && batchSeen && freshMove)throw new SearchExecution.ResourceLimit();
        }
    }
    private static <T> T interrupted(Supplier<T> action){
        var observer=new AfterProgram();
        try(var scope=RetainedOperation.open(observer)){
            observer.scope=scope;observer.initialize();return action.get();
        } finally {RetainedOperation.work(observer.work);}
    }
    private record InterruptedProvider(NativeProgramMoveProvider delegate) implements NativeMoveProvider,RetainedGraph.View {
        @Override public MoveProvider.Descriptor descriptor(){return delegate.descriptor();}
        @Override public IncrementalProviderContract.Mathematics mathematicalKind(){return delegate.mathematicalKind();}
        @Override public Batch candidates(TypedMoveSearch.State state,TypedMoveSearch.Context context){return interrupted(()->delegate.candidates(state,context));}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(delegate);}
    }
    private static final class InterruptedVerifier implements NativeVerifier,RetainedGraph.View {
        private final NativeProgramMoveProvider delegate;private final int stopAt;private int calls;
        InterruptedVerifier(NativeProgramMoveProvider delegate,int stopAt){this.delegate=delegate;this.stopAt=stopAt;}
        @Override public NativeVerification verify(TypedMoveSearch.State state,NativeSearchMove move,TypedMoveSearch.Context context){
            return ++calls==stopAt?interrupted(()->delegate.verify(state,move,context)):delegate.verify(state,move,context);
        }
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(delegate);}
    }
    private static NativeProgramMoveProvider provider(){
        var a=PatternExpr.var("A");var zero=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,a,PatternExpr.num(0)),a);
        var one=new PatternRewriteRule("one",PatternExpr.op(BinaryOperator.MUL,a,PatternExpr.num(1)),a);
        var program=new CompiledLinearRewriteEngine(new RewriteProgram.Sequence(RewriteProgram.NodeMetadata.named("cleanup"),List.of(
            new RewriteProgram.Source(RewriteProgram.NodeMetadata.named("zero-stage"),new PreparedAstRewriteTransformationEngine(List.of(zero),64,128)),
            new RewriteProgram.Source(RewriteProgram.NodeMetadata.named("one-stage"),new PreparedAstRewriteTransformationEngine(List.of(one),64,128)))),128).compileAst();
        return new NativeProgramMoveProvider(new MoveProvider.Descriptor("cleanup","cleanup",SearchMove.SourceKind.LEARNED,SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"cleanup"),program);
    }
    private static NativeMoveSearch.Problem problem(NativeMoveProvider provider,NativeVerifier verifier){return problem(provider,verifier,MoveSearch.Scheduling.STAGED);}
    private static NativeMoveSearch.Problem problem(NativeMoveProvider provider,NativeVerifier verifier,MoveSearch.Scheduling scheduling){
        var goal=new VariableExpr("x");var source=new BinaryExpr(new BinaryExpr(goal,BinaryOperator.MUL,new NumberExpr(1)),BinaryOperator.ADD,new NumberExpr(0));
        return new NativeMoveSearch.Problem(source,TypedMoveSearch.Context.frozen(goal),List.of(provider),MoveSearch.Mode.FAST,scheduling,
            new MoveSearch.Budget(2,1,0,10,100000000),NativeMovePriorityPolicy.INVENTORY_ORDER,NativeMoveSearch.ZeroScore.INSTANCE,NativeStateValue.NONE,verifier);
    }
    @Test void completedGenerationWorkSurvivesFailureBeforeThePickerReceivesItsBatch(){
        for(var scheduling:List.of(MoveSearch.Scheduling.STAGED,MoveSearch.Scheduling.EAGER_CONTROL,MoveSearch.Scheduling.STAGED_INCREMENTAL)) {
        var delegate=provider();var problem=problem(new InterruptedProvider(delegate),NativeVerifier.registered(List.of(delegate)),scheduling);
        var work=delegate.program().transformMeasured(problem.source()).workMetrics();
        var result=new NativeMoveSearch().search(problem,SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE,result.observedOutcome());
        assertEquals(work.candidateWork().canonicalWorkUnits(),result.metrics().primitiveWork(),"real completed program generation cannot disappear at its native handoff");
        assertTrue(result.metrics().searchWork()>=work.totalWorkUnits());assertEquals(0,result.metrics().consumedSuccessors());
        }
    }
    private static void verificationFailure(int stopAt){
        var delegate=provider();var verifier=new InterruptedVerifier(delegate,stopAt);var problem=problem(delegate,verifier);
        var batch=delegate.program().transformMeasured(problem.source());
        long regeneration=Math.addExact(batch.workMetrics().totalWorkUnits(),batch.workMetrics().candidateWork().canonicalWorkUnits());
        var state=new TypedMoveSearch.State(problem.source(),0,0,"",List.of(),Set.of(),0);
        long full=delegate.verify(state,delegate.proposal(batch.candidates().getFirst(),0),problem.context()).work();
        var result=new NativeMoveSearch().search(problem,SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE,result.observedOutcome());assertEquals(stopAt,verifier.calls);
        assertEquals(batch.workMetrics().candidateWork().canonicalWorkUnits(),result.metrics().primitiveWork());
        if(stopAt==1)assertEquals(regeneration,result.metrics().verificationWork(),"regeneration is complete; comparison never began");
        else {assertEquals(full,result.metrics().verificationWork());assertEquals(regeneration,result.replayWork(),"aborted independent final regeneration stays paid");}
    }
    @Test void admissionKeepsCompletedRegenerationBeforeComparisonAborts(){verificationFailure(1);}
    @Test void finalReplayKeepsCompletedRegenerationBeforeComparisonAborts(){verificationFailure(2);}
}
