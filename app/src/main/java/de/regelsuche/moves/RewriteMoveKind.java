package de.regelsuche.moves;

/**
 * Enumerates the kinds of rewrite moves that the discovery search can apply.
 *
 * <p>The declaration order is significant: it defines the deterministic
 * {@code ruleOrdinal} used by {@link MoveOrdinal}. New kinds must be appended
 * (never inserted in the middle) so that previously computed ordinals stay
 * reproducible across runs.</p>
 */
public enum RewriteMoveKind {
    NORMALIZE,
    EXPAND,
    FACTOR,
    SUBSTITUTE_INTRODUCE,
    SUBSTITUTE_EXPAND,
    COMPLETE_SQUARE,
    DIFFERENCE_OF_SQUARES,
    SOPHIE_GERMAIN,
    COMMON_SUBEXPRESSION,
    ADD_SAME_TERM_BOTH_SIDES,
    MULTIPLY_SAME_TERM_BOTH_SIDES,
    LEARNED_MACRO,
    CURATED_MACRO,
    IMPORTED_RULE,
    UNKNOWN;

    /**
     * @return the deterministic, stable ordinal used to order moves of this
     *     kind. Backed by the declaration order so it is reproducible.
     */
    public int registryOrdinal() {
        return ordinal();
    }

    /** @return whether this move kind represents an expandable macro. */
    public boolean isMacro() {
        return this == LEARNED_MACRO || this == CURATED_MACRO;
    }
}
