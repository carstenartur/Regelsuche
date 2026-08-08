package de.regelsuche.evolution;

import java.util.Map;

/** Verified public-randomness evidence bound to one frozen showcase candidate. */
public record ProofCarryingShowcasePublicRandomnessReceipt(
    String schema,
    String showcaseId,
    String planContentHash,
    String candidateFreezeContentHash,
    String network,
    String chainHash,
    long round,
    long roundUnixTime,
    String randomness,
    String signature,
    String previousSignature,
    String chainInfoHash,
    String verificationClient,
    String verificationClientArtifactHash,
    String verificationEvidenceHash,
    String verificationStatus,
    String endpointId,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.proof-carrying-showcase-public-randomness-receipt/v1";
    public static final String STATUS =
        "VERIFIED_BY_PINNED_DRAND_CLIENT";

    public ProofCarryingShowcasePublicRandomnessReceipt {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported public-randomness receipt schema");
        }
        showcaseId = ProofCarryingShowcaseJsonSupport.requireText(
            showcaseId, "showcaseId");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            planContentHash, "planContentHash");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            candidateFreezeContentHash,
            "candidateFreezeContentHash");
        network = ProofCarryingShowcaseJsonSupport.requireText(
            network, "network");
        ProofCarryingShowcaseJsonSupport.requireHex64(
            chainHash, "chainHash");
        if (round < 1 || roundUnixTime < 1) {
            throw new IllegalArgumentException(
                "drand round and round time must be positive");
        }
        ProofCarryingShowcaseJsonSupport.requireHex64(
            randomness, "randomness");
        signature = ProofCarryingShowcaseJsonSupport
            .requireBoundedHex(
                signature, 96, 1024, "signature");
        previousSignature = ProofCarryingShowcaseJsonSupport
            .requireBoundedHex(
                previousSignature,
                96,
                1024,
                "previousSignature");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            chainInfoHash, "chainInfoHash");
        verificationClient = ProofCarryingShowcaseJsonSupport
            .requireClient(verificationClient);
        ProofCarryingShowcaseJsonSupport.requireSha256(
            verificationClientArtifactHash,
            "verificationClientArtifactHash");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            verificationEvidenceHash,
            "verificationEvidenceHash");
        if (!STATUS.equals(verificationStatus)) {
            throw new IllegalArgumentException(
                "unverified public randomness is not admissible");
        }
        endpointId = ProofCarryingShowcaseJsonSupport.requireEndpoint(
            endpointId);
        ProofCarryingShowcaseJsonSupport.requireSha256(
            contentHash, "contentHash");
        String expected = ProofCarryingShowcaseJsonSupport.hashPayload(
            payload(
                schema,
                showcaseId,
                planContentHash,
                candidateFreezeContentHash,
                network,
                chainHash,
                round,
                roundUnixTime,
                randomness,
                signature,
                previousSignature,
                chainInfoHash,
                verificationClient,
                verificationClientArtifactHash,
                verificationEvidenceHash,
                verificationStatus,
                endpointId));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "public-randomness receipt contentHash mismatch");
        }
    }

    public static ProofCarryingShowcasePublicRandomnessReceipt create(
        ProofCarryingShowcasePlan plan,
        ProofCarryingShowcaseCandidateFreeze candidate,
        long round,
        long roundUnixTime,
        String randomness,
        String signature,
        String previousSignature,
        String chainInfoHash,
        String verificationClient,
        String verificationClientArtifactHash,
        String verificationEvidenceHash,
        String endpointId
    ) {
        candidate.requireCompatible(plan);
        Map<String, Object> payload = payload(
            SCHEMA,
            plan.showcaseId(),
            plan.contentHash(),
            candidate.contentHash(),
            plan.publicRandomness().network(),
            plan.publicRandomness().chainHash(),
            round,
            roundUnixTime,
            randomness,
            signature,
            previousSignature,
            chainInfoHash,
            verificationClient,
            verificationClientArtifactHash,
            verificationEvidenceHash,
            STATUS,
            endpointId);
        return new ProofCarryingShowcasePublicRandomnessReceipt(
            SCHEMA,
            plan.showcaseId(),
            plan.contentHash(),
            candidate.contentHash(),
            plan.publicRandomness().network(),
            plan.publicRandomness().chainHash(),
            round,
            roundUnixTime,
            randomness,
            signature,
            previousSignature,
            chainInfoHash,
            verificationClient,
            verificationClientArtifactHash,
            verificationEvidenceHash,
            STATUS,
            endpointId,
            ProofCarryingShowcaseJsonSupport.hashPayload(payload));
    }

    public static ProofCarryingShowcasePublicRandomnessReceipt
            fromCanonicalJson(String json) {
        return ProofCarryingShowcaseJsonSupport.read(
            json,
            ProofCarryingShowcasePublicRandomnessReceipt.class,
            "public-randomness receipt");
    }

    public String toCanonicalJson() {
        return ProofCarryingShowcaseJsonSupport.toCanonicalJson(this);
    }

    public void requireCompatible(
        ProofCarryingShowcasePlan plan,
        ProofCarryingShowcaseCandidateFreeze candidate
    ) {
        candidate.requireCompatible(plan);
        if (!showcaseId.equals(plan.showcaseId())
                || !planContentHash.equals(plan.contentHash())
                || !candidateFreezeContentHash.equals(
                    candidate.contentHash())
                || !network.equals(plan.publicRandomness().network())
                || !chainHash.equals(
                    plan.publicRandomness().chainHash())) {
            throw new IllegalArgumentException(
                "public-randomness receipt identity mismatch");
        }
        if (roundUnixTime <= candidate.randomnessNotBeforeUnixTime()) {
            throw new IllegalArgumentException(
                "drand round is not strictly after the candidate boundary");
        }
    }

    private static Map<String, Object> payload(
        String schema,
        String showcaseId,
        String planContentHash,
        String candidateFreezeContentHash,
        String network,
        String chainHash,
        long round,
        long roundUnixTime,
        String randomness,
        String signature,
        String previousSignature,
        String chainInfoHash,
        String verificationClient,
        String verificationClientArtifactHash,
        String verificationEvidenceHash,
        String verificationStatus,
        String endpointId
    ) {
        return ProofCarryingShowcaseJsonSupport.payload(
            "schema", schema,
            "showcaseId", showcaseId,
            "planContentHash", planContentHash,
            "candidateFreezeContentHash",
                candidateFreezeContentHash,
            "network", network,
            "chainHash", chainHash,
            "round", round,
            "roundUnixTime", roundUnixTime,
            "randomness", randomness,
            "signature", signature,
            "previousSignature", previousSignature,
            "chainInfoHash", chainInfoHash,
            "verificationClient", verificationClient,
            "verificationClientArtifactHash",
                verificationClientArtifactHash,
            "verificationEvidenceHash",
                verificationEvidenceHash,
            "verificationStatus", verificationStatus,
            "endpointId", endpointId);
    }
}
