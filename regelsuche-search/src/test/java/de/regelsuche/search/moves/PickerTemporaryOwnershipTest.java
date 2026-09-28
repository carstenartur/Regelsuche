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
        Ranking(Observation observation){this.observation=observation;}
        @Override public double score(Expr move){
            var retained=RetainedGraph.measure(observation.scope).retained();
            if(observation.scoredNodes.isEmpty())observation.firstScoreReferences=retained.references();
            observation.lastScoreReferences=retained.references();
            observation.scoredNodes.add(retained.nodes());
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
            assertEquals(List.of(3L,3L,3L),observation.scoredNodes,
                "every scoring callback must observe the whole live batch, including not-yet-ranked siblings");
            assertTrue(observation.lastScoreReferences-observation.firstScoreReferences>=6,
                "two prior ranks retain two list entries and two move references, in addition to the two observation entries");
        }
        assertTrue(observation.work>0);
    }
    @Test void eagerScoringIncludesItsNotYetPublishedBatch(){check(false);}
    @Test void stagedScoringIncludesItsLiveBatch(){check(true);}
}
