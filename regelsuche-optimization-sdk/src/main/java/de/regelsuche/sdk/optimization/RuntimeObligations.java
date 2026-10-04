package de.regelsuche.sdk.optimization;

/** Emit original checks in occurrence order before replacement checks and output commits. */
public record RuntimeObligations(GuardKind guard, SourceEvaluationTrace originalTrace,
        SourceEvaluationTrace replacementTrace, boolean checkIntegralRange, boolean requireFinite,
        boolean compareFloatingPointBits, long estimatedCheckWork, int fallbackOperationCount) {
    public enum GuardKind { NONE, ORIGINAL_AND_REPLACEMENT_RANGE, FINITE_AND_BITWISE_EQUAL }
    public RuntimeObligations {
        java.util.Objects.requireNonNull(guard); java.util.Objects.requireNonNull(originalTrace); java.util.Objects.requireNonNull(replacementTrace);
        if (estimatedCheckWork < 0 || fallbackOperationCount < 0) throw new IllegalArgumentException("INVALID_GUARD_COST");
    }
}
