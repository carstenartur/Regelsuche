package de.regelsuche.moves.hypothesis;

/**
 * Classifies the structural role a subterm plays inside its parent.
 *
 * <p>Declaration order is significant: it defines the deterministic tie-breaking
 * order used when occurrences are sorted. New roles must be appended, never
 * inserted, to keep ordinals reproducible.</p>
 */
public enum TermRole {
    /** The whole expression (no parent). */
    ROOT,
    /** One side of an equation. */
    EQUATION_SIDE,
    /** A summand of an additive ({@code +}/{@code -}) parent. */
    SUMMAND,
    /** A factor of a multiplicative ({@code *}/{@code /}) parent. */
    FACTOR,
    /** The base of a power ({@code base ^ exponent}). */
    EXPONENT_BASE,
    /** The exponent of a power ({@code base ^ exponent}). */
    EXPONENT,
    /** An argument of a function call. */
    ARGUMENT
}
