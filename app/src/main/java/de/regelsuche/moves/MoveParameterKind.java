package de.regelsuche.moves;

/**
 * Classifies the role a {@link MoveParameter} plays inside a rewrite move.
 *
 * <p>Declaration order defines the deterministic tie-breaking order used when
 * parameters are sorted (see {@link MoveParameter#CANONICAL_ORDER}). New kinds
 * must be appended, never inserted, to keep ordinals reproducible.</p>
 */
public enum MoveParameterKind {
    SUBTERM,
    CONSTANT,
    VARIABLE,
    EXPRESSION,
    OCCURRENCE,
    PATTERN,
    REPLACEMENT,
    PLACEHOLDER,
    GENERATED,
    MACRO_ARGUMENT
}
