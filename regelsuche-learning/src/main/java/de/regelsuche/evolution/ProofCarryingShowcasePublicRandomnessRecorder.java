package de.regelsuche.evolution;

/**
 * Converts typed, independently bound drand verification evidence into the
 * public-randomness receipt accepted by the showcase seed-derivation stage.
 */
public final class ProofCarryingShowcasePublicRandomnessRecorder {
    public ProofCarryingShowcasePublicRandomnessReceipt record(
        ProofCarryingShowcasePlan plan,
        ProofCarryingShowcaseCandidateFreeze candidate,
        ProofCarryingShowcaseDrandChainInfo chainInfo,
        ProofCarryingShowcaseDrandVerificationEvidence evidence
    ) {
        evidence.requireCompatible(plan, candidate, chainInfo);
        return ProofCarryingShowcasePublicRandomnessReceipt.create(
            plan,
            candidate,
            evidence.round(),
            evidence.roundUnixTime(),
            evidence.randomness(),
            evidence.signature(),
            evidence.previousSignature(),
            chainInfo.contentHash(),
            evidence.verificationClient(),
            evidence.verificationClientArtifactHash(),
            evidence.contentHash(),
            evidence.endpointId());
    }
}
