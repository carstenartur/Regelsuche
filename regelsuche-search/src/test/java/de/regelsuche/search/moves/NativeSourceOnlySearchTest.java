package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.search.program.AstTransportObservation;
import de.regelsuche.transform.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeSourceOnlySearchTest {
    private static final long BUDGET=1_000_000;
    private static final class Counter implements RetainedGraph.View {
        int value;
        @Override public void retainedReferences(RetainedGraph.Visitor v){}
    }
    private enum Objective implements TypedSourceOnlySearch.Objective,RetainedGraph.View {
        QUALITY, BEST;
        @Override public TypedSourceOnlySearch.Score evaluate(TypedMoveSearch.State state){
            return new TypedSourceOnlySearch.Score(state.expression() instanceof BinaryExpr?3:this==BEST?Long.MIN_VALUE:1,1);
        }
        @Override public void retainedReferences(RetainedGraph.Visitor v){}
    }
    /** Explicit trusted checker fixture executes the real primitive verifier before changing its second receipt. */
    private record Checker(NativeMoveSearch.Primitive primitive,Counter checks,int finalMode) implements NativeVerifier,RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(primitive);v.reference(checks);}
        @Override public NativeVerification verify(TypedMoveSearch.State state,NativeSearchMove move,TypedMoveSearch.Context context){
            var verified=primitive.verify(state,move,context);
            if(++checks.value!=2 || finalMode==0)return verified;
            return finalMode==1?new NativeVerification(false,37,null,null,"deliberate final rejection"):
                new NativeVerification(true,1001,verified.checkedProof(),verified.ruleId(),verified.reason());
        }
    }
    @Test void sourceOnlyQualityPaysAFreshIndependentReplayBeforeAnyExport() {
        var checks=new Counter();var problem=problem(checks,0,BUDGET);
        var leaf=((BinaryExpr)problem.source()).left();
        NativeMoveSearch.QualityResult result;
        try(var transport=AstTransportObservation.open()) {
            result=new NativeMoveSearch().searchUntil(problem,Objective.QUALITY,1,SearchContinuationContract.PATH_SENSITIVE);
            assertEquals(MoveSearch.Outcome.QUALITY_REACHED,result.search().observedOutcome());assertSame(leaf,result.incumbent().expression());
            assertEquals(3,result.inputScore());assertEquals(1,result.outputScore());assertEquals(2,checks.value);
            assertTrue(result.replayWork()>0);assertEquals(result.search().totalWork(),result.totalWork());
            assertTrue(result.totalWork()>result.search().metrics().totalWork()+result.replayWork());assertFalse(result.withinBudget());assertTrue(result.totalWork()<=result.workBudget());
            assertEquals(0,transport.total(),"native selection, admission and independent final replay must avoid the codec");
        }
        checks.value=0;
        var overrun=new NativeMoveSearch().searchUntil(problem(checks,0,result.totalWork()-1),Objective.QUALITY,1,SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(2,checks.value);assertFalse(overrun.withinBudget());assertEquals(result.totalWork(),overrun.totalWork());
    }
    @Test void finalReplayFailureAndOverrunRetainAllAttemptedWork() {
        var checks=new Counter();
        var failure=assertThrows(NativeMoveSearch.FinalCheckFailure.class,()->new NativeMoveSearch().searchUntil(problem(checks,1,BUDGET),
            Objective.QUALITY,1,SearchContinuationContract.PATH_SENSITIVE));
        assertEquals(2,checks.value);assertEquals(37,failure.attempted().replayWork());
        assertFalse(failure.rejected().accepted());assertTrue(failure.attempted().totalWork()>37);
        checks.value=0;
        var normal=new NativeMoveSearch().searchUntil(problem(checks,0,BUDGET),Objective.QUALITY,1,SearchContinuationContract.PATH_SENSITIVE);
        checks.value=0;
        var overrun=assertThrows(NativeMoveSearch.FinalCheckFailure.class,()->new NativeMoveSearch().searchUntil(problem(checks,2,normal.totalWork()+500),
            Objective.QUALITY,1,SearchContinuationContract.PATH_SENSITIVE));
        assertEquals(2,checks.value);assertEquals(1001,overrun.attempted().replayWork());assertFalse(overrun.attempted().withinBudget());
    }
    @Test void targetSearchAlsoRunsAFreshIndependentFinalReplay() {
        var checks=new Counter();var target=target(problem(checks,0,BUDGET));
        try(var transport=AstTransportObservation.open()) {
            var result=new NativeMoveSearch().search(target,SearchContinuationContract.PATH_SENSITIVE);
            assertEquals(MoveSearch.Outcome.TARGET_REACHED,result.observedOutcome());assertEquals(target.context().goal(),result.output());
            assertEquals(2,checks.value);assertEquals(0,transport.total());assertTrue(result.replayWork()>0);
            assertTrue(result.totalWork()>result.metrics().totalWork()+result.replayWork());assertFalse(result.withinBudget());assertTrue(result.totalWork()<=result.workBudget());
            checks.value=0;
            var limited=new NativeMoveSearch().search(target(problem(checks,0,result.totalWork()-1)),SearchContinuationContract.PATH_SENSITIVE);
            assertEquals(2,checks.value);assertFalse(limited.withinBudget());assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED,limited.observedOutcome());
            assertEquals(result.totalWork(),limited.totalWork());
        }
    }
    @Test void targetFinalFailureRetainsAttemptedPaidWork() {
        var checks=new Counter();
        var failure=assertThrows(NativeMoveSearch.TargetCheckFailure.class,()->new NativeMoveSearch().search(target(problem(checks,1,BUDGET)),SearchContinuationContract.PATH_SENSITIVE));
        assertEquals(2,checks.value);assertEquals(37,failure.attempted().replayWork());
        assertFalse(failure.rejected().accepted());assertTrue(failure.attempted().totalWork()>37);
    }
    @Test void bestBudgetUsesTheSameObjectiveWithoutEarlyQualityStop() {
        var checks=new Counter();
        var result=new NativeMoveSearch().searchBest(problem(checks,0,BUDGET),Objective.BEST,SearchContinuationContract.PATH_SENSITIVE);
        assertNotEquals(MoveSearch.Outcome.QUALITY_REACHED,result.search().observedOutcome());
        assertEquals(Long.MIN_VALUE,result.outputScore());assertEquals(1,result.witness().size());assertEquals(2,checks.value);
    }
    private static NativeMoveSearch.Problem target(NativeMoveSearch.Problem sourceOnly){
        return new NativeMoveSearch.Problem(sourceOnly.source(),TypedMoveSearch.Context.frozen(((BinaryExpr)sourceOnly.source()).left()),
            sourceOnly.providers(),sourceOnly.mode(),sourceOnly.scheduling(),sourceOnly.budget(),sourceOnly.policy(),sourceOnly.stateScore(),sourceOnly.stateValue(),sourceOnly.verifier());
    }
    private static NativeMoveSearch.Problem problem(Counter checks,int finalMode,long budget) {
        var leaf=new VariableExpr("x");var source=new BinaryExpr(leaf,BinaryOperator.ADD,new NumberExpr(0));
        var zero=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,PatternExpr.var("A"),PatternExpr.num(0)),PatternExpr.var("A"));
        var descriptor=new MoveProvider.Descriptor("zero","zero",SearchMove.SourceKind.PRIMITIVE,SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"quality-v1");
        var primitive=new NativeMoveSearch.Primitive(descriptor,new AstRewriteTransport(List.of(zero),32,32));
        return new NativeMoveSearch.Problem(source,TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION),List.of(primitive),MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(2,2,0,10,budget),NativeMovePriorityPolicy.INVENTORY_ORDER,NativeMoveSearch.ZeroScore.INSTANCE,NativeStateValue.NONE,
            new Checker(primitive,checks,finalMode));
    }
}
