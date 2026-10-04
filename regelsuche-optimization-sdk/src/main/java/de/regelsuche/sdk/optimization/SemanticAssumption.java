package de.regelsuche.sdk.optimization;
/** A caller-established fact with a source provenance, never a conjecture added by search. */
public record SemanticAssumption(Kind kind, String subject, String parameter, String provenance) {
    public enum Kind {
        BIG_INTEGER_VALUE_SEMANTICS, BIG_INTEGER_BIT_LENGTH_BOUND, NON_NEGATIVE_UPPER_BOUND,
        NON_NEGATIVE, POSITIVE, NORMALIZED_MODULAR_INPUT,
        NO_NAN_PAYLOAD_OBSERVATION
    }
    public SemanticAssumption {
        java.util.Objects.requireNonNull(kind);
        if (subject == null || subject.isBlank() || parameter == null || provenance == null || provenance.isBlank())
            throw new IllegalArgumentException("ASSUMPTION_PROVENANCE_REQUIRED");
    }
}
