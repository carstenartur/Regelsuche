package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.search.program.*;
import de.regelsuche.transform.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeProgramMoveProviderTest {
    @Test void nativeGenerationPreservesTheLegacyHistoryMetadataBoundary() {
        var a=PatternExpr.var("A");
        var zero=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,a,PatternExpr.num(0)),a);
        var program=new CompiledLinearRewriteEngine(new RewriteProgram.Sequence(RewriteProgram.NodeMetadata.named("p".repeat(4097)),List.of(
            new RewriteProgram.Source(RewriteProgram.NodeMetadata.named("zero-stage"),new PreparedAstRewriteTransformationEngine(List.of(zero),64,128)))),128).compileAst();
        var descriptor=new MoveProvider.Descriptor("cleanup","cleanup",SearchMove.SourceKind.LEARNED,SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"cleanup-v1");
        var target=new VariableExpr("x");
        var source=new BinaryExpr(target,BinaryOperator.ADD,new NumberExpr(0));
        var history=program.transformMeasured(source).candidates().getFirst();
        assertThrows(IllegalArgumentException.class,()->new TypedProgramMoveProvider(descriptor,program).proposal(history));
        var provider=new NativeProgramMoveProvider(descriptor,program);
        var state=new TypedMoveSearch.State(source,0,0,"",List.of(),java.util.Set.of(),0);
        try(var transport=AstTransportObservation.open()) {
            assertThrows(IllegalArgumentException.class,()->provider.candidates(state,TypedMoveSearch.Context.frozen(target)));
            assertEquals(0,transport.total(),"native guards must not serialize the history");
        }
    }

    @Test void registeredProgramKeepsItsProducerHistoryThroughTheSharedFrontierAndLegacyExport() {
        var a=PatternExpr.var("A");
        var zero=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,a,PatternExpr.num(0)),a);
        var one=new PatternRewriteRule("one",PatternExpr.op(BinaryOperator.MUL,a,PatternExpr.num(1)),a);
        var program=new CompiledLinearRewriteEngine(new RewriteProgram.Sequence(RewriteProgram.NodeMetadata.named("cleanup"),List.of(
            new RewriteProgram.Source(RewriteProgram.NodeMetadata.named("zero-stage"),new PreparedAstRewriteTransformationEngine(List.of(zero),64,128)),
            new RewriteProgram.Source(RewriteProgram.NodeMetadata.named("one-stage"),new PreparedAstRewriteTransformationEngine(List.of(one),64,128)))),128).compileAst();
        var descriptor=new MoveProvider.Descriptor("cleanup","cleanup",SearchMove.SourceKind.LEARNED,SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"cleanup-v1");
        var goal=NumberExpr.exact("1/3");var source=new BinaryExpr(new BinaryExpr(goal,BinaryOperator.MUL,new NumberExpr(1)),BinaryOperator.ADD,new NumberExpr(0));
        var budget=new MoveSearch.Budget(2,1,0,10,1000000);var context=TypedMoveSearch.Context.frozen(goal);
        var old=new TypedProgramMoveProvider(descriptor,program);
        var legacy=new TypedMoveSearch().search(new TypedMoveSearch.Problem(source,context,List.of(old),MovePriorityPolicy.INVENTORY_ORDER,old.verifier(),s->0,MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,budget));
        NativeMoveSearch.Result result;
        try(var transport=AstTransportObservation.open()) {
        result=assertDoesNotThrow(()->new NativeMoveSearch().search(new NativeMoveSearch.Problem(source,context,List.of(new NativeProgramMoveProvider(descriptor,program)),MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,budget),SearchContinuationContract.PATH_SENSITIVE));
        System.out.println("P04_INVENTORY_PROGRAM outcome="+result.observedOutcome()+" total="+result.totalWork()+" budget="+result.workBudget()+" withinBudget="+result.withinBudget()+" replay="+result.replayWork()+" validation="+result.accounting().validationWork()+" execution="+result.accounting().executionWork()+" storage="+result.accounting().storageWork()+" retention="+result.accounting().retentionWork()+" peak="+result.accounting().peak()+" retained="+result.accounting().resultRetained()+" external="+result.accounting().externalRetained()+" metrics="+result.metrics());
        assertEquals(MoveSearch.Outcome.TARGET_REACHED,result.observedOutcome(),()->result.accounting().detail()+" total="+result.totalWork()+" retention="+result.accounting().retentionWork()+" replay="+result.replayWork()+" metrics="+result.metrics());assertSame(goal,result.output());
        assertEquals(2,result.witness().getFirst().move().primitiveStepCount());
        assertEquals(0,transport.total(),"native program admission must not invoke any codec");
        }
        try(var transport=AstTransportObservation.open()) {
            var expected=legacy.encodedResult();
            assertEquals(new MoveSearch.Result(MoveSearch.Outcome.INCONCLUSIVE,expected.witness(),expected.events(),expected.reachedStates(),expected.deadEndStates(),
                expected.metrics(),false,expected.stateAssessments(),expected.incrementalExecution(),expected.stagedIncrementalExecution()),result.exportLegacy(NativeMoveSearch.Result.DEFAULT_EXPORT_WORK,SearchExpressionStore.Limits.DEFAULT).projection());
            assertTrue(transport.total()>0,"explicit export exercises the measured codec");
        }
        var accountedProblem=new NativeMoveSearch.Problem(source,context,List.of(new NativeProgramMoveProvider(descriptor,program)),MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(2,1,0,10,1000000));
        var accounted=new NativeMoveSearch().search(accountedProblem,SearchContinuationContract.PATH_SENSITIVE,SearchExpressionStore.Limits.DEFAULT);
        assertEquals(MoveSearch.Outcome.TARGET_REACHED,accounted.observedOutcome(),accounted.accounting().detail());
        assertTrue(accounted.accounting().validationWork()>0);assertTrue(accounted.accounting().executionWork()>0);
        assertFalse(accounted.withinBudget());assertTrue(accounted.totalWork()<=accounted.workBudget());assertEquals(2,accounted.witness().getFirst().move().primitiveStepCount());
        var provider=new NativeProgramMoveProvider(descriptor,program);
        var history=program.transformMeasured(source).candidates().getFirst();
        var forged=new CompiledAstRewriteProgram.Candidate(history.programId(),List.of("foreign-stage",history.sourceIds().getLast()),history.steps());
        var sourceState=new TypedMoveSearch.State(source,0,0,"",List.of(),java.util.Set.of(),0);
        var rejected=provider.verify(sourceState,provider.proposal(forged,0),context);
        assertFalse(rejected.accepted(),"same endpoints cannot authorize a different program stage");
        assertTrue(rejected.work()>0,"failed independent regeneration remains paid");
    }
}
