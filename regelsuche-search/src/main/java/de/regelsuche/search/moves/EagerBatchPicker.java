package de.regelsuche.search.moves;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;

import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Explicit eager control. Stable ties preserve inventory order; every score is evaluated once. */
final class EagerBatchPicker<M> implements SearchExecution.Picker<M>,RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(generated);v.reference(batchReceipts);v.reference(work);v.reference(providers);v.reference(policy);}

    private final List<IncrementalProviderContract.Snapshot> batchReceipts=new ArrayList<>();
    @Override public List<IncrementalProviderContract.Snapshot> batchCursorReceipts(){return batchReceipts;}

    private record Ranked<M>(M move, double score) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(move);}
    }
    private List<M> generated=new ArrayList<>();
    private TransformationWorkMetrics work=TransformationWorkMetrics.ZERO;
    private List<SearchBatches.Provider<M>> providers;
    private SearchBatches.Ranking<M> policy;
    private boolean complete,initialized;
    private int cursor;

    EagerBatchPicker(List<SearchBatches.Provider<M>> providers, SearchBatches.Ranking<M> policy) {
        this(providers,policy,true);
    }
    /** The frontier owns the picker before atomic opening can exhaust native resources. */
    EagerBatchPicker(List<SearchBatches.Provider<M>> providers, SearchBatches.Ranking<M> policy,boolean openImmediately) {
        this.providers=List.copyOf(providers);this.policy=policy;
        if(openImmediately)initialize();
    }
    @Override public void initialize() {
        if(initialized)return;
        initialized=true;
        var ranked=new ArrayList<Ranked<M>>();
        try(var retained=RetainedOperation.retain(this,ranked)) {
            work=work.withDelegatedMechanicalWork(policy.contextWork());
            boolean exhaustive=true;
            for(var provider:providers) {
                var batch=provider.candidates();
                work=work.plus(batch.work());
                batchReceipts.addAll(batch.cursorReceipts());
                de.regelsuche.retention.RetainedOperation.work(batch.cursorReceipts().size());
                generated.addAll(batch.moves());
                exhaustive&=batch.complete();
                try(var held=RetainedOperation.retain(batch)) {
                    RetainedOperation.work(batch.moves().size());
                    for(var move:batch.moves()) {
                        policy.requireSource(move);
                        work=work.plus(ordering(1));
                        double score=policy.score(move);
                        if(!Double.isFinite(score))throw new IllegalArgumentException("nonfinite move priority");
                        ranked.add(new Ranked<>(move,score));
                        RetainedOperation.work(2);RetainedOperation.checkpoint();
                    }
                }
            }
            ranked.sort(Comparator.<Ranked<M>>comparingDouble(Ranked::score).reversed());
            var ordered=new ArrayList<M>();
            try(var held=RetainedOperation.retain(ordered)) {
                for(var rank:ranked){ordered.add(rank.move());RetainedOperation.work(1);}
                generated=RetainedOperation.produced(List.copyOf(ordered));
                RetainedOperation.work(ordered.size());
            }
            complete=exhaustive;
        } finally {providers=null;policy=null;RetainedOperation.work(2);}
    }
    private static TransformationWorkMetrics ordering(int count) {
        return new TransformationWorkMetrics(0,0,0,0,0,0,0,count,0,0,0,0,0,0);
    }
    @Override public Optional<M> next() { initialize(); return cursor == generated.size() ? Optional.empty() : Optional.of(generated.get(cursor++)); }
    @Override public TransformationWorkMetrics workMetrics() { return work; }
    @Override public List<M> generatedMoves() { return generated; }
    @Override public boolean complete() { return complete; }
}
