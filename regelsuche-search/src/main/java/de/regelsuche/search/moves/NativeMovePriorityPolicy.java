package de.regelsuche.search.moves;

/** Ranking consumes actual immutable expressions; it never authorizes an edge. */
@FunctionalInterface
public interface NativeMovePriorityPolicy {
    double score(NativeSearchMove move,TypedMoveSearch.State state,TypedMoveSearch.Context context);
    default MovePriorityPolicy.Stage stage(MoveProvider.Descriptor provider,TypedMoveSearch.State state,TypedMoveSearch.Context context) {
        return MovePriorityPolicy.INVENTORY_ORDER.stage(provider,null,null);
    }
    default double providerScore(MoveProvider.Descriptor provider,TypedMoveSearch.State state,TypedMoveSearch.Context context){return 0;}
    default long contextWork(TypedMoveSearch.State state,TypedMoveSearch.Context context){return 0;}
    NativeMovePriorityPolicy INVENTORY_ORDER=(move,state,context)->0;
}
