package de.regelsuche.sdk.optimization;
/** Explicit contract strengthening. Tiny-and-inexact underflow is reserved and rejected in revision 1. */
public record CheckedPolicy(String revision, boolean checkIntegralRange, boolean requireFinite,
        boolean compareFloatingPointBits, boolean checkTinyInexactUnderflow) {
    public static final String REVISION = "java-checked/v1";
    public static final CheckedPolicy NONE = new CheckedPolicy(REVISION, false, false, false, false);
    public static final CheckedPolicy EXPLICIT_DEFAULT = new CheckedPolicy(REVISION, true, true, true, false);
    public CheckedPolicy {
        if (revision == null || revision.isBlank()) throw new IllegalArgumentException("CHECKED_REVISION_REQUIRED");
    }
}
