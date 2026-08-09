package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProofCarryingShowcaseDrandVerificationEvidenceTest {
    @Test
    void verifiedEvidenceBindsTheExactEligibleRoundAndCreatesReceipt() {
        ProofCarryingShowcasePlan plan =
            ProofCarryingShowcaseTestFixtures.plan();
        ProofCarryingShowcaseCandidateFreeze candidate =
            ProofCarryingShowcaseTestFixtures.candidate(plan);
        ProofCarryingShowcaseDrandChainInfo chain =
            ProofCarryingShowcaseDrandChainInfo.createDefault();
        String signature = "cd".repeat(96);
        long round = chain.firstEligibleScheduledRound(
            candidate.randomnessNotBeforeUnixTime());
        ProofCarryingShowcaseDrandVerificationEvidence evidence =
            ProofCarryingShowcaseDrandVerificationEvidence
                .createFromVerifiedClientOutput(
                    plan,
                    candidate,
                    chain,
                    round,
                    ProofCarryingShowcaseDrandVerificationEvidence
                        .randomnessFromSignature(signature),
                    signature,
                    "ef".repeat(96),
                    hash("drand-client-1.4.2-bundle"),
                    "api.drand.sh");

        ProofCarryingShowcasePublicRandomnessReceipt receipt =
            new ProofCarryingShowcasePublicRandomnessRecorder().record(
                plan, candidate, chain, evidence);

        assertEquals(round, receipt.round());
        assertEquals(chain.roundUnixTime(round), receipt.roundUnixTime());
        assertEquals(chain.contentHash(), receipt.chainInfoHash());
        assertEquals(
            ProofCarryingShowcaseDrandVerificationEvidence
                .VERIFICATION_CLIENT,
            receipt.verificationClient());
        assertEquals(
            evidence.verificationClientArtifactHash(),
            receipt.verificationClientArtifactHash());
        assertEquals(evidence.contentHash(), receipt.verificationEvidenceHash());
        assertEquals(evidence.randomness(), receipt.randomness());
        assertEquals(
            ProofCarryingShowcasePublicRandomnessReceipt.STATUS,
            receipt.verificationStatus());
    }

    @Test
    void laterRoundSubstitutionIsRejected() {
        ProofCarryingShowcasePlan plan =
            ProofCarryingShowcaseTestFixtures.plan();
        ProofCarryingShowcaseCandidateFreeze candidate =
            ProofCarryingShowcaseTestFixtures.candidate(plan);
        ProofCarryingShowcaseDrandChainInfo chain =
            ProofCarryingShowcaseDrandChainInfo.createDefault();
        String signature = "cd".repeat(96);
        long first = chain.firstEligibleScheduledRound(
            candidate.randomnessNotBeforeUnixTime());

        assertThrows(
            IllegalArgumentException.class,
            () -> ProofCarryingShowcaseDrandVerificationEvidence
                .createFromVerifiedClientOutput(
                    plan,
                    candidate,
                    chain,
                    first + 1,
                    ProofCarryingShowcaseDrandVerificationEvidence
                        .randomnessFromSignature(signature),
                    signature,
                    "ef".repeat(96),
                    hash("drand-client-1.4.2-bundle"),
                    "api.drand.sh"));
    }

    @Test
    void signatureRandomnessMismatchAndClientDriftFailClosed() {
        ProofCarryingShowcasePlan plan =
            ProofCarryingShowcaseTestFixtures.plan();
        ProofCarryingShowcaseCandidateFreeze candidate =
            ProofCarryingShowcaseTestFixtures.candidate(plan);
        ProofCarryingShowcaseDrandChainInfo chain =
            ProofCarryingShowcaseDrandChainInfo.createDefault();
        String signature = "cd".repeat(96);
        long round = chain.firstEligibleScheduledRound(
            candidate.randomnessNotBeforeUnixTime());
        ProofCarryingShowcaseDrandVerificationEvidence evidence =
            ProofCarryingShowcaseDrandVerificationEvidence
                .createFromVerifiedClientOutput(
                    plan,
                    candidate,
                    chain,
                    round,
                    ProofCarryingShowcaseDrandVerificationEvidence
                        .randomnessFromSignature(signature),
                    signature,
                    "ef".repeat(96),
                    hash("drand-client-1.4.2-bundle"),
                    "api.drand.sh");

        assertThrows(
            IllegalArgumentException.class,
            () -> new ProofCarryingShowcaseDrandVerificationEvidence(
                evidence.schema(),
                evidence.planContentHash(),
                evidence.candidateFreezeContentHash(),
                evidence.chainInfoContentHash(),
                evidence.chainHash(),
                evidence.publicKey(),
                evidence.round(),
                evidence.roundUnixTime(),
                "00".repeat(32),
                evidence.signature(),
                evidence.previousSignature(),
                evidence.verificationClient(),
                evidence.verificationClientArtifactHash(),
                evidence.endpointId(),
                evidence.verificationStatus(),
                evidence.contentHash()));
        assertThrows(
            IllegalArgumentException.class,
            () -> new ProofCarryingShowcaseDrandVerificationEvidence(
                evidence.schema(),
                evidence.planContentHash(),
                evidence.candidateFreezeContentHash(),
                evidence.chainInfoContentHash(),
                evidence.chainHash(),
                evidence.publicKey(),
                evidence.round(),
                evidence.roundUnixTime(),
                evidence.randomness(),
                evidence.signature(),
                evidence.previousSignature(),
                "drand-client/1.4.1",
                evidence.verificationClientArtifactHash(),
                evidence.endpointId(),
                evidence.verificationStatus(),
                evidence.contentHash()));
    }

    private static String hash(String value) {
        return EvolutionGenome.hash(value);
    }
}
