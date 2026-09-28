package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.search.program.AstTransportObservation;
import de.regelsuche.transform.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativePrimitiveWorkTest {
    @Test void independentPrimitiveAdmissionAndFinalReplayPayActualRegenerationMathematics(){
        var a=PatternExpr.var("A");var zero=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,a,PatternExpr.num(0)),a);
        var target=new VariableExpr("x");var source=new BinaryExpr(target,BinaryOperator.ADD,new NumberExpr(0));
        var descriptor=new MoveProvider.Descriptor("zero","zero",SearchMove.SourceKind.PRIMITIVE,SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"zero");
        var provider=new NativeMoveSearch.Primitive(descriptor,new AstRewriteTransport(List.of(zero),64,128));
        var state=new TypedMoveSearch.State(source,0,0,"",List.of(),Set.of(),0);var context=TypedMoveSearch.Context.frozen(target);
        try(var transport=AstTransportObservation.open()){
            var generated=provider.candidates(state,context);
            long actualGeneration=generated.work().totalWorkUnitsV2();
            assertEquals(1,generated.work().candidateWork().primitiveRewrites());
            var checked=NativeVerifier.registered(List.of(provider)).verify(state,generated.moves().getFirst(),context);
            assertTrue(checked.accepted());
            assertEquals(actualGeneration,checked.work(),"fresh primitive verification performs the same paid generation, including its mathematical rewrite");
            var result=new NativeMoveSearch().search(new NativeMoveSearch.Problem(source,context,List.of(provider),MoveSearch.Mode.FAST,
                MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(1,1,0,10,10000000)),SearchContinuationContract.PATH_SENSITIVE);
            assertEquals(MoveSearch.Outcome.TARGET_REACHED,result.outcome());assertTrue(result.withinBudget());
            assertEquals(actualGeneration,result.metrics().verificationWork());assertEquals(actualGeneration,result.replayWork());
            assertEquals(0,transport.total(),"paid regeneration remains native until explicit export");
        }
    }
}
