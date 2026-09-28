package de.regelsuche.search.moves;

/** Explicit native expression continuation, using the same managed P02/P03 contract. */
public interface ExprIncrementalProvider extends NativeMoveProvider {
    IncrementalProviderContract.Definition contractDefinition();
    IncrementalProviderContract.ObjectCursor<NativeMoveProof> openSession(TypedMoveSearch.State state,TypedMoveSearch.Context context);
}
