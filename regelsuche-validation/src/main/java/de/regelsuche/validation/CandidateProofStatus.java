package de.regelsuche.validation;

/**
 * Lifecycle of a mined rule candidate or reusable inventory rule.
 *
 * <p>Ordering is used by inventory, discovery and proof threshold checks;
 * {@code REJECTED} is intentionally placed
 * <strong>before</strong> {@code OBSERVED} so candidates explicitly excluded
 * from the inventory never satisfy any positive minimum.</p>
 */
public enum CandidateProofStatus {
    REJECTED,
    OBSERVED,
    VALIDATED_BY_EXAMPLES,
    SYMBOLICALLY_VERIFIED,
    FORMALLY_PROVABLE,
    FORMALLY_PROVED;

    /**
     * @return whether this status indicates a candidate that has been (or could be)
     *         positively validated, i.e. not {@link #REJECTED}.
     */
    public boolean isPositive() {
        return this != REJECTED;
    }

    /**
     * @return whether this status meets or exceeds {@code other}.
     */
    public boolean atLeast(CandidateProofStatus other) {
        return ordinal() >= other.ordinal();
    }
}
