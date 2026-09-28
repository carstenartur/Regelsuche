package de.regelsuche.search.moves;

import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.List;

/** Explicit object transport. Legacy marker interfaces do not imply this contract. */
public interface NativeMoveProvider {
    MoveProvider.Descriptor descriptor();
    /** Execution dimension, independent of evidence strength; required for managed native batches. */
    default IncrementalProviderContract.Mathematics mathematicalKind(){
        throw new IllegalArgumentException("native provider requires an explicit mathematical work contract");
    }
    record Batch(List<NativeSearchMove> moves,TransformationWorkMetrics work,boolean complete,
            List<IncrementalProviderContract.Snapshot> cursorReceipts) implements de.regelsuche.retention.RetainedGraph.View {
        @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(moves);v.reference(work);v.reference(cursorReceipts);}
        public Batch(List<NativeSearchMove> moves,TransformationWorkMetrics work,boolean complete){this(moves,work,complete,List.of());}
        public Batch { moves=List.copyOf(moves);cursorReceipts=List.copyOf(cursorReceipts); }
    }
    static boolean carries(List<String> required,TypedMoveSearch.State source,TypedMoveSearch.Context context) {
        var available=new java.util.HashSet<>(context.initialAssumptions());available.addAll(source.assumptions());return available.containsAll(required);
    }
    static Batch rejectedAssumptions(){return new Batch(List.of(),new TransformationWorkMetrics(0,0,0,0,0,1,1,0,0,0,0,0,0,0),true);}
    default Batch candidates(TypedMoveSearch.State source,TypedMoveSearch.Context context) {
        throw new UnsupportedOperationException("native provider generation is not implemented");
    }
    /** Convenience for trusted built-in checkers; an arbitrary provider implementation is never default authority. */
    default NativeVerification verify(TypedMoveSearch.State source,NativeSearchMove move,TypedMoveSearch.Context context) {
        throw new UnsupportedOperationException("native provider verification is not implemented");
    }
}
