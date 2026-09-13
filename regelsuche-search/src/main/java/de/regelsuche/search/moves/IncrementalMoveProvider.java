package de.regelsuche.search.moves;

import de.regelsuche.transform.TransformationCursor;

/** Explicit native capability. This first revision does not admit opaque, learned or plugin providers. */
public sealed interface IncrementalMoveProvider extends MoveProvider permits NativeIncrementalMoveProvider {
    TransformationCursor.Definition definition();
    /** Checks provider admission once, before opening/charging a transformation cursor. */
    TransformationCursor openCursor(MoveState state, MoveContext context);

    /** Typed admission rejection so the picker retains the check without treating it as a generation failure. */
    final class AssumptionsNotCarried extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        AssumptionsNotCarried() { super("native provider assumptions are not carried by the source context"); }
    }

    /** A batch adapter would discard this revision's suspension and work contract. */
    @Override default Batch candidates(MoveState state, MoveContext context) {
        throw new UnsupportedOperationException("incremental providers require INCREMENTAL_NATIVE_ORDER scheduling");
    }
}
