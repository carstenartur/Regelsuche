package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.search.program.*;
import de.regelsuche.transform.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeProgramMoveProviderTest {
    @Test void registeredProgramKeepsItsProducerHistoryThroughTheSharedFrontierAndLegacyExport() {
        var a=PatternExpr.var("A");
        var zero=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,a,PatternExpr.num(0)),a);
        var one=new PatternRewriteRule("one",PatternExpr.op(BinaryOperator.MUL,a,PatternExpr.num(1)),a);
        var program=new CompiledLinearRewriteEngine(new RewriteProgram.Sequence(RewriteProgram.NodeMetadata.named("cleanup"),List.of(
            new RewriteProgram.Source(RewriteProgram.NodeMetadata.named("zero-stage"),new PreparedAstRewriteTransformationEngine(List.of(zero),64,128)),
            new RewriteProgram.Source(RewriteProgram.NodeMetadata.named("one-stage"),new PreparedAstRewriteTransformationEngine(List.of(one),64,128)))),128).compileAst();
        var descriptor=new MoveProvider.Descriptor("cleanup","cleanup",SearchMove.SourceKind.LEARNED,SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"cleanup-v1");
        var goal=NumberExpr.exact("1/3");var source=new BinaryExpr(new BinaryExpr(goal,BinaryOperator.MUL,new NumberExpr(1)),BinaryOperator.ADD,new NumberExpr(0));
        var budget=new MoveSearch.Budget(2,1,0,10,10000);var context=TypedMoveSearch.Context.frozen(goal);
        var old=new TypedProgramMoveProvider(descriptor,program);
        var legacy=new TypedMoveSearch().search(new TypedMoveSearch.Problem(source,context,List.of(old),MovePriorityPolicy.INVENTORY_ORDER,old.verifier(),s->0,MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,budget));
        var result=assertDoesNotThrow(()->new NativeMoveSearch().search(new NativeMoveSearch.Problem(source,context,List.of(new NativeProgramMoveProvider(descriptor,program)),MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,budget),SearchContinuationContract.PATH_SENSITIVE));
        assertEquals(MoveSearch.Outcome.TARGET_REACHED,result.outcome());assertSame(goal,result.output());
        assertEquals(2,result.witness().getFirst().move().primitiveStepCount());
        assertEquals(legacy.encodedResult(),result.exportLegacy());
    }
}
