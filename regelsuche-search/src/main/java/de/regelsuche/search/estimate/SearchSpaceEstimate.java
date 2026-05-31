package de.regelsuche.search.estimate;

/**
 * Immutable result of a {@link SearchSpaceEstimator} run.
 *
 * <p>Mirrors the GUI/CLI fields requested in issue #74 ("Suchraumabschätzung"):
 * the number of states already known, the estimated growth (branching) rate, the
 * expected total search-space size if the search keeps growing at that rate up to
 * the depth bound, a coarse {@link SearchSpaceRisk} level and a human-readable
 * warning ({@code null} when there is nothing to warn about).</p>
 *
 * @param knownStateCount          number of states observed so far
 * @param estimatedBranchingFactor estimated average number of successor states per
 *                                 state (the growth rate); {@code 1.0} means a
 *                                 stable frontier, values below {@code 1.0} a
 *                                 shrinking one
 * @param projectedStateCount      expected total number of states if growth
 *                                 continues at {@code estimatedBranchingFactor} up
 *                                 to the depth bound (saturated, never overflows)
 * @param risk                     coarse explosion-risk classification
 * @param warning                  human-readable warning, or {@code null} when the
 *                                 search is expected to stay within budget
 */
public record SearchSpaceEstimate(
    long knownStateCount,
    double estimatedBranchingFactor,
    long projectedStateCount,
    SearchSpaceRisk risk,
    String warning
) {
    public SearchSpaceEstimate {
        if (knownStateCount < 0) {
            throw new IllegalArgumentException("knownStateCount must not be negative");
        }
        if (projectedStateCount < 0) {
            throw new IllegalArgumentException("projectedStateCount must not be negative");
        }
        if (Double.isNaN(estimatedBranchingFactor) || estimatedBranchingFactor < 0) {
            throw new IllegalArgumentException("estimatedBranchingFactor must be a non-negative number");
        }
        if (risk == null) {
            throw new IllegalArgumentException("risk must not be null");
        }
    }

    /** Whether the estimator produced a warning the user should see. */
    public boolean hasWarning() {
        return warning != null;
    }
}
