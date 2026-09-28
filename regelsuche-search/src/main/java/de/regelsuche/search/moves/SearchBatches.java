package de.regelsuche.search.moves;

import de.regelsuche.retention.RetainedGraph;

import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.List;

/** Representation-free batch opening/ranking; candidates retain their producer metadata. */
final class SearchBatches {
    private SearchBatches() {}
    record Batch<M>(List<M> moves,TransformationWorkMetrics work,boolean complete,
            List<IncrementalProviderContract.Snapshot> cursorReceipts) implements RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(moves);v.reference(work);v.reference(cursorReceipts);}

        Batch(List<M> moves,TransformationWorkMetrics work,boolean complete){this(moves,work,complete,List.of());}
        Batch { moves=List.copyOf(moves);cursorReceipts=List.copyOf(cursorReceipts); }
    }
    interface Provider<M> extends RetainedGraph.View {
        @Override default void retainedReferences(RetainedGraph.Visitor v){v.requireExact(this,Void.class);}
 MoveProvider.Descriptor descriptor(); Batch<M> candidates(); }
    interface Ranking<M> extends RetainedGraph.View {
        @Override default void retainedReferences(RetainedGraph.Visitor v){v.requireExact(this,Void.class);}

        double score(M move); int stage(MoveProvider.Descriptor descriptor);
        double providerScore(MoveProvider.Descriptor descriptor); long contextWork(); void requireSource(M move);
    }
    static List<Provider<SearchMove>> legacyProviders(List<MoveProvider> providers,MoveState state,MoveContext context) {
        return providers.stream().<Provider<SearchMove>>map(provider->new Provider<>() {
            @Override public MoveProvider.Descriptor descriptor(){return provider.descriptor();}
            @Override public Batch<SearchMove> candidates(){var batch=provider.candidates(state,context);return new Batch<>(batch.moves(),batch.work(),batch.complete());}
        }).toList();
    }
    static Ranking<SearchMove> legacyRanking(MovePriorityPolicy policy,MoveState state,MoveContext context) {
        return new Ranking<>() {
            @Override public double score(SearchMove move){return policy.score(move,state,context);}
            @Override public int stage(MoveProvider.Descriptor provider){return policy.stage(provider,state,context).ordinal();}
            @Override public double providerScore(MoveProvider.Descriptor provider){return policy.providerScore(provider,state,context);}
            @Override public long contextWork(){return policy.contextWork(state,context);}
            @Override public void requireSource(SearchMove move){move.requireSource(state.expression());}
        };
    }
}
