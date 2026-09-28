package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.transform.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NativeSourceOnlySearchTest {
    @Test void sourceOnlyQualityPaysAFreshIndependentReplayBeforeAnyExport() {
        var leaf=new VariableExpr("x");var source=new BinaryExpr(leaf,BinaryOperator.ADD,new NumberExpr(0));
        var zero=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,PatternExpr.var("A"),PatternExpr.num(0)),PatternExpr.var("A"));
        var descriptor=new MoveProvider.Descriptor("zero","zero",SearchMove.SourceKind.PRIMITIVE,SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"quality-v1");
        var primitive=new NativeMoveSearch.Primitive(descriptor,new AstRewriteTransport(List.of(zero),32,32));
        var checks=new AtomicInteger();
        var provider=new NativeMoveProvider(){
            @Override public MoveProvider.Descriptor descriptor(){return descriptor;}
            @Override public Batch candidates(TypedMoveSearch.State state,TypedMoveSearch.Context context){return primitive.candidates(state,context);}
            @Override public NativeVerification verify(TypedMoveSearch.State state,NativeSearchMove move,TypedMoveSearch.Context context){checks.incrementAndGet();return primitive.verify(state,move,context);}
        };
        var problem=new NativeMoveSearch.Problem(source,TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION),List.of(provider),MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(2,2,0,10,1000));
        var result=assertDoesNotThrow(()->new NativeMoveSearch().searchUntil(problem,s->new TypedSourceOnlySearch.Score(s.expression() instanceof BinaryExpr?3:1,1),1,SearchContinuationContract.PATH_SENSITIVE));
        assertEquals(MoveSearch.Outcome.QUALITY_REACHED,result.search().outcome());assertSame(leaf,result.incumbent().expression());
        assertEquals(3,result.inputScore());assertEquals(1,result.outputScore());
        assertEquals(2,checks.get(),"admission and final replay must both execute the real independent verifier");
        assertTrue(result.replayWork()>0);assertEquals(result.search().metrics().totalWork()+result.replayWork(),result.totalWork());assertTrue(result.withinBudget());
        checks.set(0);
        var small=new NativeMoveSearch.Problem(source,problem.context(),problem.providers(),problem.mode(),problem.scheduling(),new MoveSearch.Budget(2,2,0,10,result.totalWork()-1));
        var overrun=new NativeMoveSearch().searchUntil(small,s->new TypedSourceOnlySearch.Score(s.expression() instanceof BinaryExpr?3:1,1),1,SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(2,checks.get());assertFalse(overrun.withinBudget());assertEquals(result.totalWork(),overrun.totalWork());
    }
    @Test void finalReplayFailureAndOverrunRetainAllAttemptedWork() {
        var checks=new AtomicInteger();
        var problem=problem(checks,true,1000);
        var failure=assertThrows(NativeMoveSearch.FinalCheckFailure.class,()->new NativeMoveSearch().searchUntil(problem,
            s->new TypedSourceOnlySearch.Score(s.expression() instanceof BinaryExpr?3:1,1),1,SearchContinuationContract.PATH_SENSITIVE));
        assertEquals(2,checks.get());assertEquals(37,failure.attempted().replayWork());
        assertFalse(failure.rejected().accepted());assertTrue(failure.attempted().totalWork()>37);
        checks.set(0);
        var overrun=assertThrows(NativeMoveSearch.FinalCheckFailure.class,()->new NativeMoveSearch().searchUntil(problem(checks,false,1000),
            s->new TypedSourceOnlySearch.Score(s.expression() instanceof BinaryExpr?3:1,1),1,SearchContinuationContract.PATH_SENSITIVE));
        assertEquals(1001,overrun.attempted().replayWork());assertFalse(overrun.attempted().withinBudget());
    }
    @Test void bestBudgetUsesTheSameObjectiveWithoutEarlyQualityStop() {
        var checks=new AtomicInteger();
        var result=new NativeMoveSearch().searchBest(problem(checks,false,10000),
            s->new TypedSourceOnlySearch.Score(s.expression() instanceof BinaryExpr?3:Long.MIN_VALUE,1),SearchContinuationContract.PATH_SENSITIVE);
        assertNotEquals(MoveSearch.Outcome.QUALITY_REACHED,result.search().outcome());
        assertEquals(Long.MIN_VALUE,result.outputScore());assertEquals(1,result.witness().size());assertEquals(2,checks.get());
    }
    private static NativeMoveSearch.Problem problem(AtomicInteger checks,boolean reject,long budget) {
        var leaf=new VariableExpr("x");var source=new BinaryExpr(leaf,BinaryOperator.ADD,new NumberExpr(0));
        var zero=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,PatternExpr.var("A"),PatternExpr.num(0)),PatternExpr.var("A"));
        var descriptor=new MoveProvider.Descriptor("zero","zero",SearchMove.SourceKind.PRIMITIVE,SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"quality-v1");
        var primitive=new NativeMoveSearch.Primitive(descriptor,new AstRewriteTransport(List.of(zero),32,32));
        var provider=new NativeMoveProvider(){
            @Override public MoveProvider.Descriptor descriptor(){return descriptor;}
            @Override public Batch candidates(TypedMoveSearch.State state,TypedMoveSearch.Context context){return primitive.candidates(state,context);}
            @Override public NativeVerification verify(TypedMoveSearch.State state,NativeSearchMove move,TypedMoveSearch.Context context){
                var verified=primitive.verify(state,move,context);
                if(checks.incrementAndGet()==1 || budget==10000)return verified;
                return reject?new NativeVerification(false,37,null,null,"deliberate final rejection"):
                    new NativeVerification(true,1001,verified.checkedProof(),verified.ruleId(),verified.reason());
            }
        };
        return new NativeMoveSearch.Problem(source,TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION),List.of(provider),MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(2,2,0,10,budget));
    }
}
