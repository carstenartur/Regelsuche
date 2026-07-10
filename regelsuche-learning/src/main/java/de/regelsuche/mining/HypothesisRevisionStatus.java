package de.regelsuche.mining;

/**
 * Lifecycle status of a {@link HypothesisRevision} in the counterexample-guided
 * refinement loop.
 *
 * <pre>
 * PROPOSED
 *   → CHALLENGED          (counterexample search submitted)
 *     → COUNTEREXAMPLE_FOUND  (counterexample disproves this revision)
 *       → REFINED             (a refinement strategy produced a successor revision)
 *         → CHALLENGED_AGAIN  (successor revision enters a new challenge round)
 *           → VALIDATED_WITHIN_BUDGET  (no counterexample found within budget – terminal accept)
 *           → REJECTED                 (budget exhausted or no strategy can help – terminal reject)
 *           → INCONCLUSIVE             (search ended without a reliable verdict – terminal)
 *     → VALIDATED_WITHIN_BUDGET  (fast path: first revision survives challenge)
 *     → REJECTED                 (first revision disproved and cannot be refined)
 *     → INCONCLUSIVE             (first challenge inconclusive)
 * </pre>
 *
 * <p>Terminal statuses are {@link #VALIDATED_WITHIN_BUDGET}, {@link #REJECTED}
 * and {@link #INCONCLUSIVE}.</p>
 */
public enum HypothesisRevisionStatus {

    /** Initial state; counterexample search has not yet been submitted. */
    PROPOSED,

    /** Counterexample search is in progress or has been submitted for this revision. */
    CHALLENGED,

    /** A counterexample was found that disproves this specific revision. */
    COUNTEREXAMPLE_FOUND,

    /** A refinement strategy created a successor revision from this revision. */
    REFINED,

    /** The successor revision is undergoing another challenge round. */
    CHALLENGED_AGAIN,

    /**
     * No counterexample was found within the configured budget; this revision
     * is accepted as valid under the budget constraint (terminal).
     */
    VALIDATED_WITHIN_BUDGET,

    /**
     * This revision was disproved and either the revision budget was exhausted,
     * no refinement strategy could apply, or a cycle was detected (terminal).
     */
    REJECTED,

    /**
     * The counterexample search ended without a reliable verdict and the
     * revision cannot be classified further (terminal).
     */
    INCONCLUSIVE;

    /** @return {@code true} if this is a terminal status that ends the refinement loop. */
    public boolean isTerminal() {
        return this == VALIDATED_WITHIN_BUDGET || this == REJECTED || this == INCONCLUSIVE;
    }
}
