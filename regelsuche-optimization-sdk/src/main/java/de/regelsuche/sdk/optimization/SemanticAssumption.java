package de.regelsuche.sdk.optimization;
/** A caller-established fact with a source provenance, never a conjecture added by search. */
public record SemanticAssumption(Kind kind, String subject, String parameter, String provenance) {
    public enum Kind {
        BIG_INTEGER_VALUE_SEMANTICS, BIG_INTEGER_BIT_LENGTH_BOUND, NON_NEGATIVE_UPPER_BOUND,
        NON_NEGATIVE, POSITIVE, NORMALIZED_MODULAR_INPUT, MODULAR_UNIT_INPUT,
        SUCCESSFUL_MODULAR_INVERSE,
        NO_NAN_PAYLOAD_OBSERVATION
    }
    public SemanticAssumption {
        java.util.Objects.requireNonNull(kind);
        if (subject == null || subject.isBlank() || parameter == null || provenance == null || provenance.isBlank())
            throw new IllegalArgumentException("ASSUMPTION_PROVENANCE_REQUIRED");
    }
    /** A conditional source law premise, not an execution observation. The caller must
     * establish success of this exact retained operation before applying the law.
     */
    public static SemanticAssumption successfulModularInverse(SourceEvaluationTrace.Occurrence occurrence,String provenance) {
        return new SemanticAssumption(Kind.SUCCESSFUL_MODULAR_INVERSE,occurrence.sourceId(),SuccessfulInverseFacts.commitment(occurrence,null),provenance);
    }
}
