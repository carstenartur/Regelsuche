package de.regelsuche.evolution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Content-addressed evidence returned by a pinned drand client after BLS
 * verification of the single preregistered eligible showcase round.
 *
 * <p>The external client owns BLS verification. Java independently checks all
 * experiment bindings, scheduled round time, and the drand invariant that the
 * public randomness equals SHA-256 of the verified signature.</p>
 */
public record ProofCarryingShowcaseDrandVerificationEvidence(
    String schema,
    String planContentHash,
    String candidateFreezeContentHash,
    String chainInfoContentHash,
    String chainHash,
    String publicKey,
    long round,
    long roundUnixTime,
    String randomness,
    String signature,
    String previousSignature,
    String verificationClient,
    String verificationClientArtifactHash,
    String endpointId,
    String verificationStatus,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.proof-carrying-showcase-drand-verification-evidence/v1";
    public static final String VERIFICATION_CLIENT = "drand-client/1.4.2";
    public static final String STATUS =
        "BEACON_VERIFIED_BY_PINNED_DRAND_CLIENT";

    public ProofCarryingShowcaseDrandVerificationEvidence {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported drand verification-evidence schema");
        }
        ProofCarryingShowcaseJsonSupport.requireSha256(
            planContentHash, "planContentHash");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            candidateFreezeContentHash, "candidateFreezeContentHash");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            chainInfoContentHash, "chainInfoContentHash");
        ProofCarryingShowcaseJsonSupport.requireHex64(
            chainHash, "chainHash");
        ProofCarryingShowcaseJsonSupport.requireBoundedHex(
            publicKey, 96, 96, "publicKey");
        if (round < 1 || roundUnixTime < 1) {
            throw new IllegalArgumentException(
                "verified drand round and time must be positive");
        }
        ProofCarryingShowcaseJsonSupport.requireHex64(
            randomness, "randomness");
        signature = ProofCarryingShowcaseJsonSupport.requireBoundedHex(
            signature, 96, 1024, "signature");
        previousSignature = ProofCarryingShowcaseJsonSupport
            .requireBoundedHex(
                previousSignature, 96, 1024, "previousSignature");
        if (!VERIFICATION_CLIENT.equals(verificationClient)) {
            throw new IllegalArgumentException(
                "drand verification client version drift");
        }
        ProofCarryingShowcaseJsonSupport.requireClient(verificationClient);
        ProofCarryingShowcaseJsonSupport.requireSha256(
            verificationClientArtifactHash,
            "verificationClientArtifactHash");
        endpointId = ProofCarryingShowcaseJsonSupport.requireEndpoint(
            endpointId);
        if (!STATUS.equals(verificationStatus)) {
            throw new IllegalArgumentException(
                "drand evidence is not client-verified");
        }
        if (!randomness.equals(randomnessFromSignature(signature))) {
            throw new IllegalArgumentException(
                "drand randomness is not SHA-256(signature)");
        }
        ProofCarryingShowcaseJsonSupport.requireSha256(
            contentHash, "contentHash");
        String expected = ProofCarryingShowcaseJsonSupport.hashPayload(
            payload(
                schema,
                planContentHash,
                candidateFreezeContentHash,
                chainInfoContentHash,
                chainHash,
                publicKey,
                round,
                roundUnixTime,
                randomness,
                signature,
                previousSignature,
                verificationClient,
                verificationClientArtifactHash,
                endpointId,
                verificationStatus));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "drand verification-evidence contentHash mismatch");
        }
    }

    public static ProofCarryingShowcaseDrandVerificationEvidence
            createFromVerifiedClientOutput(
        ProofCarryingShowcasePlan plan,
        ProofCarryingShowcaseCandidateFreeze candidate,
        ProofCarryingShowcaseDrandChainInfo chainInfo,
        long round,
        String randomness,
        String signature,
        String previousSignature,
        String verificationClientArtifactHash,
        String endpointId
    ) {
        chainInfo.requireCompatible(plan);
        candidate.requireCompatible(plan);
        long expectedRound = chainInfo.firstEligibleScheduledRound(
            candidate.randomnessNotBeforeUnixTime());
        if (round != expectedRound) {
            throw new IllegalArgumentException(
                "only the first eligible scheduled drand round is admissible");
        }
        long roundTime = chainInfo.roundUnixTime(round);
        if (roundTime <= candidate.randomnessNotBeforeUnixTime()) {
            throw new IllegalArgumentException(
                "drand round is not strictly after candidate boundary");
        }
        Map<String, Object> payload = payload(
            SCHEMA,
            plan.contentHash(),
            candidate.contentHash(),
            chainInfo.contentHash(),
            chainInfo.chainHash(),
            chainInfo.publicKey(),
            round,
            roundTime,
            randomness,
            signature,
            previousSignature,
            VERIFICATION_CLIENT,
            verificationClientArtifactHash,
            endpointId,
            STATUS);
        return new ProofCarryingShowcaseDrandVerificationEvidence(
            SCHEMA,
            plan.contentHash(),
            candidate.contentHash(),
            chainInfo.contentHash(),
            chainInfo.chainHash(),
            chainInfo.publicKey(),
            round,
            roundTime,
            randomness,
            signature,
            previousSignature,
            VERIFICATION_CLIENT,
            verificationClientArtifactHash,
            endpointId,
            STATUS,
            ProofCarryingShowcaseJsonSupport.hashPayload(payload));
    }

    public static ProofCarryingShowcaseDrandVerificationEvidence
            fromCanonicalJson(String json) {
        return ProofCarryingShowcaseJsonSupport.read(
            json,
            ProofCarryingShowcaseDrandVerificationEvidence.class,
            "drand verification evidence");
    }

    public String toCanonicalJson() {
        return ProofCarryingShowcaseJsonSupport.toCanonicalJson(this);
    }

    public void requireCompatible(
        ProofCarryingShowcasePlan plan,
        ProofCarryingShowcaseCandidateFreeze candidate,
        ProofCarryingShowcaseDrandChainInfo chainInfo
    ) {
        chainInfo.requireCompatible(plan);
        candidate.requireCompatible(plan);
        long expectedRound = chainInfo.firstEligibleScheduledRound(
            candidate.randomnessNotBeforeUnixTime());
        if (!planContentHash.equals(plan.contentHash())
                || !candidateFreezeContentHash.equals(candidate.contentHash())
                || !chainInfoContentHash.equals(chainInfo.contentHash())
                || !chainHash.equals(chainInfo.chainHash())
                || !publicKey.equals(chainInfo.publicKey())
                || round != expectedRound
                || roundUnixTime != chainInfo.roundUnixTime(round)
                || roundUnixTime <= candidate.randomnessNotBeforeUnixTime()) {
            throw new IllegalArgumentException(
                "drand verification evidence binding mismatch");
        }
    }

    static String randomnessFromSignature(String signature) {
        String checked = ProofCarryingShowcaseJsonSupport.requireBoundedHex(
            signature, 96, 1024, "signature");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(HexFormat.of().parseHex(checked));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static Map<String, Object> payload(
        String schema,
        String planContentHash,
        String candidateFreezeContentHash,
        String chainInfoContentHash,
        String chainHash,
        String publicKey,
        long round,
        long roundUnixTime,
        String randomness,
        String signature,
        String previousSignature,
        String verificationClient,
        String verificationClientArtifactHash,
        String endpointId,
        String verificationStatus
    ) {
        return ProofCarryingShowcaseJsonSupport.payload(
            "schema", schema,
            "planContentHash", planContentHash,
            "candidateFreezeContentHash", candidateFreezeContentHash,
            "chainInfoContentHash", chainInfoContentHash,
            "chainHash", chainHash,
            "publicKey", publicKey,
            "round", round,
            "roundUnixTime", roundUnixTime,
            "randomness", randomness,
            "signature", signature,
            "previousSignature", previousSignature,
            "verificationClient", verificationClient,
            "verificationClientArtifactHash",
                verificationClientArtifactHash,
            "endpointId", endpointId,
            "verificationStatus", verificationStatus);
    }
}
