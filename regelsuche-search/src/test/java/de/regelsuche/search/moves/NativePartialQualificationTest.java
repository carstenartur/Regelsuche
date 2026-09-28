package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.search.program.AstTransportObservation;
import de.regelsuche.transform.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativePartialQualificationTest {
    private enum Objective implements TypedSourceOnlySearch.Objective,RetainedGraph.View {
        INSTANCE;
        @Override public TypedSourceOnlySearch.Score evaluate(TypedMoveSearch.State state){return new TypedSourceOnlySearch.Score(state.expression() instanceof BinaryExpr?3:1,1);}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){}
    }
    private static NativeMoveSearch.Problem problem(boolean sourceOnly,MoveSearch.Scheduling scheduling){
        var goal=new VariableExpr("x");var source=new BinaryExpr(goal,BinaryOperator.ADD,new NumberExpr(0));
        var a=PatternExpr.var("A");var rule=new PatternRewriteRule("zero",PatternExpr.op(BinaryOperator.ADD,a,PatternExpr.num(0)),a);
        var descriptor=new MoveProvider.Descriptor("zero","zero",SearchMove.SourceKind.PRIMITIVE,SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"partial-boundary");
        var context=sourceOnly?TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION):TypedMoveSearch.Context.frozen(goal);
        return new NativeMoveSearch.Problem(source,context,List.of(new NativeMoveSearch.Primitive(descriptor,new AstRewriteTransport(List.of(rule),32,32))),
            MoveSearch.Mode.FAST,scheduling,new MoveSearch.Budget(2,2,0,10,10000000));
    }
    private static void partial(NativeMoveSearch.Result result){
        assertEquals(NativeMoveSearch.Coverage.PARTIAL_ATOMIC_INVENTORY,result.coverage());
        assertTrue(result.observationsComplete());assertTrue(result.accounting().observationsComplete());
        assertFalse(result.accountingComplete(),"known unobserved atomic allocations must prevent full native accounting qualification");
        assertFalse(result.accounting().complete());assertFalse(result.withinBudget());
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE,result.outcome());
        assertTrue(result.replayWork()>0);
    }
    private static void target(NativeMoveSearch.Result result){
        partial(result);assertEquals(MoveSearch.Outcome.TARGET_REACHED,result.observedOutcome());
        assertEquals(new VariableExpr("x"),result.output());assertEquals(1,result.witness().size());
        assertTrue(result.witness().getFirst().verification().accepted());
    }
    @Test void allNativeEntryPointsPreserveMathematicsButRefuseTotalQualification(){
        var search=new NativeMoveSearch();
        try(var transport=AstTransportObservation.open()){
            for(var scheduling:List.of(MoveSearch.Scheduling.STAGED,MoveSearch.Scheduling.EAGER_CONTROL,MoveSearch.Scheduling.STAGED_INCREMENTAL)){
                target(search.search(problem(false,scheduling),SearchContinuationContract.PATH_SENSITIVE));
                target(search.search(problem(false,scheduling),SearchContinuationContract.PATH_SENSITIVE,SearchExpressionStore.Limits.DEFAULT));
                for(boolean explicit:List.of(false,true)){
                    var until=explicit?search.searchUntil(problem(true,scheduling),Objective.INSTANCE,1,SearchContinuationContract.PATH_SENSITIVE,SearchExpressionStore.Limits.DEFAULT)
                        :search.searchUntil(problem(true,scheduling),Objective.INSTANCE,1,SearchContinuationContract.PATH_SENSITIVE);
                    partial(until.search());assertEquals(MoveSearch.Outcome.QUALITY_REACHED,until.search().observedOutcome());
                    assertEquals(1,until.witness().size());assertTrue(until.witness().getFirst().verification().accepted());assertFalse(until.withinBudget());assertEquals(1,until.outputScore());assertEquals(new VariableExpr("x"),until.incumbent().expression());
                    var best=explicit?search.searchBest(problem(true,scheduling),Objective.INSTANCE,SearchContinuationContract.PATH_SENSITIVE,SearchExpressionStore.Limits.DEFAULT)
                        :search.searchBest(problem(true,scheduling),Objective.INSTANCE,SearchContinuationContract.PATH_SENSITIVE);
                    partial(best.search());assertNotEquals(MoveSearch.Outcome.QUALITY_REACHED,best.search().observedOutcome());
                    assertEquals(1,best.witness().size());assertTrue(best.witness().getFirst().verification().accepted());assertFalse(best.withinBudget());assertEquals(1,best.outputScore());assertEquals(new VariableExpr("x"),best.incumbent().expression());
                }
            }
            assertEquals(0,transport.total());
        }
    }
    @Test void explicitExportCannotQualifyItsSourceOrItsIncompleteAtomicCoverage(){
        var result=new NativeMoveSearch().search(problem(false,MoveSearch.Scheduling.STAGED),SearchContinuationContract.PATH_SENSITIVE);
        long work=result.totalWork();
        var exported=result.exportLegacy(10000000,SearchExpressionStore.Limits.DEFAULT);
        assertEquals(NativeMoveSearch.Coverage.PARTIAL_ATOMIC_INVENTORY,exported.coverage());
        assertTrue(exported.artifactAvailable());assertTrue(exported.accounting().observationsComplete());
        assertFalse(exported.complete());assertFalse(exported.accounting().complete());assertNotNull(exported.projection());
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE,exported.projection().outcome());assertFalse(exported.projection().completeBoundedRelation());
        assertEquals(1,exported.projection().witness().size());assertTrue(exported.projection().witness().getFirst().verification().accepted());
        var failure=assertThrows(NativeMoveSearch.ExportFailure.class,result::exportLegacy);
        assertTrue(failure.attempted().accounting().work()>0);assertNotNull(failure.attempted().projection());
        var exhausted=result.exportLegacy(0,SearchExpressionStore.Limits.DEFAULT);
        assertFalse(exhausted.complete());assertNull(exhausted.projection());assertTrue(exhausted.accounting().detail().contains("WORK_EXHAUSTED"));
        assertEquals(work,result.totalWork());assertFalse(result.withinBudget());
    }
}
