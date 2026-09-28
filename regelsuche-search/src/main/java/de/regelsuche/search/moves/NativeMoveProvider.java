package de.regelsuche.search.moves;

import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.List;

/** Explicit object transport. Legacy marker interfaces do not imply this contract. */
public interface NativeMoveProvider {
    MoveProvider.Descriptor descriptor();
    record Batch(List<NativeSearchMove> moves,TransformationWorkMetrics work,boolean complete) {
        public Batch { moves=List.copyOf(moves); }
    }
    static boolean carries(List<String> required,TypedMoveSearch.State source,TypedMoveSearch.Context context) {
        var available=new java.util.HashSet<>(context.initialAssumptions());available.addAll(source.assumptions());return available.containsAll(required);
    }
    static Batch rejectedAssumptions(){return new Batch(List.of(),new TransformationWorkMetrics(0,0,0,0,0,1,1,0,0,0,0,0,0,0),true);}
    default Batch candidates(TypedMoveSearch.State source,TypedMoveSearch.Context context) {
        throw new UnsupportedOperationException("native provider generation is not implemented");
    }
    default NativeVerification verify(TypedMoveSearch.State source,NativeSearchMove move,TypedMoveSearch.Context context) {
        throw new UnsupportedOperationException("native provider verification is not implemented");
    }
}
