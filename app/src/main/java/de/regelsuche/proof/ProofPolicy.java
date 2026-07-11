package de.regelsuche.proof;

/**
 * Configures how external prover confirmation is enforced as a gate for
 * discovery promotion and public evidence.
 *
 * <p>Three levels of strictness are available:</p>
 * <ul>
 *   <li>{@link #PROOF_OPTIONAL} — no external proof is required; existing
 *       example-based and symbolic validation gates apply as normal.</li>
 *   <li>{@link #PROOF_REQUIRED_FOR_PROMOTION} — a candidate cannot be
 *       promoted to the inventory unless an external prover has confirmed the
 *       equivalence ({@code PROVER_CONFIRMED} execution status).</li>
 *   <li>{@link #PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE} — in addition to
 *       promotion, the candidate may not appear in public gallery evidence
 *       without a confirmed proof.</li>
 * </ul>
 *
 * <p>Only {@link ProverExecutionResult.Status#PROVER_CONFIRMED} satisfies a
 * mandatory policy.  {@link ProverExecutionResult.Status#SCRIPT_GENERATED},
 * {@link ProverExecutionResult.Status#PROVER_NOT_AVAILABLE},
 * {@link ProverExecutionResult.Status#PROVER_TIMEOUT} and
 * {@link ProverExecutionResult.Status#PROVER_FAILED} are all distinct
 * blockers under any non-optional policy.</p>
 */
public enum ProofPolicy {

    /**
     * No external proof is required; the candidate may be promoted and shown
     * as public evidence regardless of proof execution status.
     */
    PROOF_OPTIONAL,

    /**
     * An external prover must return {@code PROVER_CONFIRMED} before the
     * candidate may be promoted to the rule inventory.
     */
    PROOF_REQUIRED_FOR_PROMOTION,

    /**
     * An external prover must return {@code PROVER_CONFIRMED} before the
     * candidate may be shown as public discovery evidence.  This is the
     * strictest setting and implies the promotion requirement as well.
     */
    PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE;

    /**
     * Checks whether {@code proverExecutionStatus} satisfies this policy.
     *
     * <p>For {@link #PROOF_OPTIONAL}, every status is accepted.
     * For all other policies only {@code "PROVER_CONFIRMED"} is accepted.</p>
     *
     * @param proverExecutionStatus the {@link ProverExecutionResult.Status}
     *        name (e.g. {@code "PROVER_CONFIRMED"}, {@code "SCRIPT_GENERATED"});
     *        {@code null} or blank is treated as {@code "SCRIPT_GENERATED"}.
     * @return {@code true} if this policy is satisfied by the given status.
     */
    public boolean satisfiedBy(String proverExecutionStatus) {
        if (this == PROOF_OPTIONAL) {
            return true;
        }
        return ProverExecutionResult.Status.PROVER_CONFIRMED.name()
            .equals(proverExecutionStatus == null ? "" : proverExecutionStatus.trim());
    }

    /**
     * Returns whether promotion is gated on external proof confirmation.
     *
     * @return {@code true} for {@link #PROOF_REQUIRED_FOR_PROMOTION} and
     *         {@link #PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE}.
     */
    public boolean requiresConfirmedProofForPromotion() {
        return this == PROOF_REQUIRED_FOR_PROMOTION || this == PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE;
    }

    /**
     * Returns whether public evidence display is gated on external proof
     * confirmation.
     *
     * @return {@code true} only for {@link #PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE}.
     */
    public boolean requiresConfirmedProofForPublicEvidence() {
        return this == PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE;
    }

    /**
     * Normalises an absent or blank execution status to a canonical sentinel.
     *
     * @param proverExecutionStatus raw status string, possibly null/blank.
     * @return {@code "SCRIPT_GENERATED"} when blank, otherwise the trimmed input.
     */
    public static String normaliseExecutionStatus(String proverExecutionStatus) {
        if (proverExecutionStatus == null || proverExecutionStatus.isBlank()) {
            return ProverExecutionResult.Status.SCRIPT_GENERATED.name();
        }
        return proverExecutionStatus.trim();
    }
}
