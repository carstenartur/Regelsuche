package de.regelsuche.moves.hypothesis;

/**
 * Identifies the mathematical reasoning that produced a {@link ParameterHypothesis}.
 *
 * <p>This is the heart of the "Search Space Intelligence": every hypothesis can
 * be traced back to the structural source it was derived from instead of being
 * blindly enumerated. Declaration order is significant for deterministic
 * ordering and must only be appended to.</p>
 */
public enum HypothesisSource {
    /** A subtree of the input considered as an atomic candidate. */
    SUBTREE,
    /** A subtree that occurs more than once and is a substitution candidate. */
    REPEATED_SUBTREE,
    /** Derived from the structural difference to the target expression. */
    TARGET_DIFF,
    /** An additive term that can be neutralised (cancelled). */
    CANCELLATION,
    /** A factor shared by several summands. */
    COMMON_FACTOR,
    /** A completing-the-square shift/residue on a skeleton. */
    COMPLETE_SQUARE,
    /** A known pattern matched on a {@link TermSkeleton}. */
    SKELETON_MATCH,
    /** An inverse operation that isolates a variable in an equation. */
    EQUATION_ISOLATION
}
