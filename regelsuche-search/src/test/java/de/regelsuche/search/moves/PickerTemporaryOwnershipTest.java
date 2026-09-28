package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PickerTemporaryOwnershipTest {
    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        final List<Long> scoredNodes=new ArrayList<>();
        long work,firstScoreReferences,lastScoreReferences;
        @Override public void executionWork(long units){work=Math.addExact(work,units);}
        @Override public void validationWork(long units){}
        @Override public void checkpoint(){}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(scope);v.reference(scoredNodes);}
    }
    private static final class Provider implements SearchBatches.Provider<Expr> {
        private final MoveProvider.Descriptor descriptor=new MoveProvider.Descriptor("batch","batch",
            SearchMove.SourceKind.PRIMITIVE,SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"batch");
        @Override public MoveProvider.Descriptor descriptor(){return descriptor;}
        @Override public SearchBatches.Batch<Expr> candidates(){
            return new SearchBatches.Batch<>(List.of(new VariableExpr("first"),new VariableExpr("second"),new VariableExpr("third")),TransformationWorkMetrics.ZERO,true);
        }
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(descriptor);}
    }
    private static final class Ranking implements SearchBatches.Ranking<Expr> {
        private final Observation observation;
        private final boolean stop;
        Ranking(Observation observation){this(observation,false);}
        Ranking(Observation observation,boolean stop){this.observation=observation;this.stop=stop;}
        @Override public double score(Expr move){
            var retained=RetainedGraph.measure(observation.scope).retained();
            if(observation.scoredNodes.isEmpty())observation.firstScoreReferences=retained.references();
            observation.lastScoreReferences=retained.references();
            observation.scoredNodes.add(retained.nodes());
            if(stop)throw new ScoreStopped();
            return 0; // Stable ties must retain provider order.
        }
        @Override public int stage(MoveProvider.Descriptor descriptor){return 0;}
        @Override public double providerScore(MoveProvider.Descriptor descriptor){return 0;}
        @Override public long contextWork(){return 0;}
        @Override public void requireSource(Expr move){}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(observation);}
    }
    private static void check(boolean staged){
        var observation=new Observation();
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;
            var providers=List.<SearchBatches.Provider<Expr>>of(new Provider());
            var ranking=new Ranking(observation);
            SearchExecution.Picker<Expr> picker=staged?new StagedBatchPicker<>(providers,ranking):new EagerBatchPicker<>(providers,ranking);
            var actual=new ArrayList<Expr>();
            try(var held=RetainedOperation.retain(picker)){
                for(var next=picker.next();next.isPresent();next=picker.next())actual.add(next.orElseThrow());
            }
            assertEquals(List.of(new VariableExpr("first"),new VariableExpr("second"),new VariableExpr("third")),actual);
            assertEquals(staged?4:3,picker.workMetrics().priorityCandidatesOrdered());
            assertEquals(List.of(3L,3L,3L),observation.scoredNodes,
                "every scoring callback must observe the whole live batch, including not-yet-ranked siblings");
            assertTrue(observation.lastScoreReferences-observation.firstScoreReferences>=6,
                "two prior ranks retain two list entries and two move references, in addition to the two observation entries");
        }
        assertTrue(observation.work>0);
    }
    @Test void eagerScoringIncludesItsNotYetPublishedBatch(){check(false);}
    @Test void stagedScoringIncludesItsLiveBatch(){check(true);}
    private static final class TemporaryScore implements NativeMovePriorityPolicy,RetainedGraph.View {
        private int calls;
        @Override public double score(NativeSearchMove move,TypedMoveSearch.State state,TypedMoveSearch.Context context){
            calls++;
            var temporary=new ArrayList<Expr>();
            for(int i=0;i<20;i++)temporary.add(new VariableExpr("score"+i));
            try(var held=RetainedOperation.retain(temporary)){return 0;}
        }
        @Override public void retainedReferences(RetainedGraph.Visitor v){}
    }
    private static void checkAbortedWork(MoveSearch.Scheduling scheduling){
        var a=de.regelsuche.transform.PatternExpr.var("A");
        var zero=new de.regelsuche.transform.PatternRewriteRule("zero",de.regelsuche.transform.PatternExpr.op(
            de.regelsuche.ast.BinaryOperator.ADD,a,de.regelsuche.transform.PatternExpr.num(0)),a);
        var goal=new VariableExpr("x");
        var source=new de.regelsuche.ast.BinaryExpr(goal,de.regelsuche.ast.BinaryOperator.ADD,new de.regelsuche.ast.NumberExpr(0));
        var provider=new NativeMoveSearch.Primitive(new Provider().descriptor(),new de.regelsuche.transform.AstRewriteTransport(List.of(zero),64,128));
        var context=TypedMoveSearch.Context.frozen(goal);
        var generated=provider.candidates(new TypedMoveSearch.State(source,0,0,"",List.of(),java.util.Set.of(),0),context);
        var policy=new TemporaryScore();
        var problem=new NativeMoveSearch.Problem(source,context,List.of(provider),MoveSearch.Mode.FAST,scheduling,
            new MoveSearch.Budget(1,1,0,10,10000000),policy,NativeMoveSearch.ZeroScore.INSTANCE,NativeStateValue.NONE);
        var engine=new NativeMoveSearch();
        var aborted=engine.search(problem,SearchContinuationContract.PATH_SENSITIVE,new SearchExpressionStore.Limits(10,1000000,1000000,10));
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE,aborted.outcome());
        assertFalse(aborted.withinBudget());assertTrue(aborted.accounting().peak().nodes()>10);
        assertEquals(generated.work().candidateWork().canonicalWorkUnits(),aborted.metrics().primitiveWork(),
            "completed provider mathematics must survive a later scoring retention abort exactly once");
        assertEquals(generated.moves().size(),aborted.metrics().generatedSuccessors());
        assertTrue(aborted.metrics().searchWork()>=generated.work().totalWorkUnits());
        assertEquals(1,policy.calls,"resource abort must not resume scoring or consume a candidate");
        assertEquals(0,aborted.metrics().consumedSuccessors());assertTrue(aborted.witness().isEmpty());
        var completed=engine.search(problem,SearchContinuationContract.PATH_SENSITIVE,SearchExpressionStore.Limits.DEFAULT);
        assertEquals(MoveSearch.Outcome.TARGET_REACHED,completed.outcome(),completed.accounting().detail());
        assertEquals(generated.work().candidateWork().canonicalWorkUnits(),completed.metrics().primitiveWork(),
            "normal collection must not double-charge the provider receipt");
        assertEquals(2,policy.calls);
    }
    @Test void eagerAbortRetainsCompletedProviderWork(){checkAbortedWork(MoveSearch.Scheduling.EAGER_CONTROL);}
    @Test void stagedAbortRetainsCompletedProviderWork(){checkAbortedWork(MoveSearch.Scheduling.STAGED);}

    private static final class ScoreStopped extends RuntimeException {}
    @Test void stagedAbortDoesNotChargeScoresThatNeverStarted(){
        var observation=new Observation();
        try(var scope=RetainedOperation.open(observation)){
            observation.scope=scope;
            var picker=new StagedBatchPicker<>(List.<SearchBatches.Provider<Expr>>of(new Provider()),new Ranking(observation,true));
            assertThrows(ScoreStopped.class,picker::next);
            assertEquals(1,observation.scoredNodes.size());
            assertEquals(2,picker.workMetrics().priorityCandidatesOrdered(),
                "one provider order plus the first attempted score; two later scores never ran");
            assertEquals(3,picker.generatedMoves().size(),"the complete generated batch remains available for accounting");
        }
    }

}
