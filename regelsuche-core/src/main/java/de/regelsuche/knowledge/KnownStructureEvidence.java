package de.regelsuche.knowledge;

/** Minimum independent evidence required before a known structure may unlock capabilities. */
public enum KnownStructureEvidence {
    OBSERVED,
    VALIDATED_BY_EXAMPLES,
    SYMBOLICALLY_VERIFIED,
    FORMALLY_PROVABLE,
    FORMALLY_PROVED;

    public boolean atLeast(KnownStructureEvidence other) {
        return ordinal() >= other.ordinal();
    }
}
