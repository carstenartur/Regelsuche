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
    }
}
