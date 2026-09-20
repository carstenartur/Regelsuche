package de.regelsuche.search.moves;

import de.regelsuche.transform.TransformationCursor;

/** Sealed incremental admission: native v1 or explicit registered v2. No arbitrary implementation admission. */
public sealed interface IncrementalMoveProvider extends MoveProvider permits NativeIncrementalMoveProvider, RegisteredIncrementalMoveProvider {
    TransformationCursor.Definition definition();
    /** General v2 metadata; a registered schema is never represented as a native rule. */
    default IncrementalProviderContract.Definition contractDefinition() {
        return StagedIncrementalSources.definition(this);
    }
    default IncrementalProviderContract.Cursor openSession(MoveState state, MoveContext context) {
        return StagedIncrementalSources.open(this, state, context, ignored -> {});
    }
    /** Checks provider admission once, before opening/charging a transformation cursor. */
    TransformationCursor openCursor(MoveState state, MoveContext context);

    /** Typed admission rejection so the picker retains the check without treating it as a generation failure. */
    final class AssumptionsNotCarried extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        AssumptionsNotCarried() { super("native provider assumptions are not carried by the source context"); }
    }

    /** A batch adapter would discard this revision's suspension and work contract. */
    @Override default Batch candidates(MoveState state, MoveContext context) {
        throw new UnsupportedOperationException("incremental providers require their explicit incremental scheduling contract");
    }
}
