package de.regelsuche.evolution;

/** Domain-separated FINAL TEST seed derivation after candidate freeze. */
public final class ProofCarryingShowcaseSeedDeriver {
    public static final String DOMAIN =
        "regelsuche.proof-carrying-showcase-seed/v1";

    private ProofCarryingShowcaseSeedDeriver() {
    }

    public static String derive(
        ProofCarryingShowcasePlan plan,
        ProofCarryingShowcaseCandidateFreeze candidate,
        ProofCarryingShowcasePublicRandomnessReceipt randomness
    ) {
        randomness.requireCompatible(plan, candidate);
        String material = String.join(
            "\n",
            DOMAIN,
            "showcaseId=" + plan.showcaseId(),
            "planContentHash=" + plan.contentHash(),
            "candidateFreezeContentHash=" + candidate.contentHash(),
            "chainHash=" + randomness.chainHash(),
            "round=" + randomness.round(),
            "randomness=" + randomness.randomness(),
            "randomnessReceiptContentHash="
                + randomness.contentHash());
        return EvolutionGenome.hash(material);
    }
}
