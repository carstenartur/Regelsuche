package de.regelsuche.evolution;

import java.util.Map;

/** Content-addressed seed receipt derived after the candidate freeze. */
public record ProofCarryingShowcaseSeedReceipt(
    String schema,
    String showcaseId,
    String planContentHash,
    String candidateFreezeContentHash,
    String randomnessReceiptContentHash,
    String drandChainHash,
    long drandRound,
    String derivationAlgorithm,
    String derivedSeed,
    String status,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.proof-carrying-showcase-seed-receipt/v1";
    public static final String DERIVATION_ALGORITHM =
        "SHA256_DOMAIN_SEPARATED_V1";
    public static final String STATUS =
        "FINAL_TEST_SEED_DERIVED_AFTER_CANDIDATE_FREEZE";

    public ProofCarryingShowcaseSeedReceipt {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported showcase seed-receipt schema");
        }
        showcaseId = ProofCarryingShowcaseJsonSupport.requireText(
            showcaseId, "showcaseId");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            planContentHash, "planContentHash");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            candidateFreezeContentHash,
            "candidateFreezeContentHash");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            randomnessReceiptContentHash,
            "randomnessReceiptContentHash");
        ProofCarryingShowcaseJsonSupport.requireHex64(
            drandChainHash, "drandChainHash");
        if (drandRound < 1) {
            throw new IllegalArgumentException(
                "drandRound must be positive");
        }
        if (!DERIVATION_ALGORITHM.equals(derivationAlgorithm)) {
            throw new IllegalArgumentException(
                "showcase seed derivation algorithm drift");
        }
        ProofCarryingShowcaseJsonSupport.requireSha256(
            derivedSeed, "derivedSeed");
        if (!STATUS.equals(status)) {
            throw new IllegalArgumentException(
                "showcase seed has an invalid stage status");
        }
        ProofCarryingShowcaseJsonSupport.requireSha256(
            contentHash, "contentHash");
        String expected = ProofCarryingShowcaseJsonSupport.hashPayload(
            payload(
                schema,
                showcaseId,
                planContentHash,
                candidateFreezeContentHash,
                randomnessReceiptContentHash,
                drandChainHash,
                drandRound,
                derivationAlgorithm,
                derivedSeed,
                status));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "showcase seed-receipt contentHash mismatch");
        }
    }

    public static ProofCarryingShowcaseSeedReceipt create(
        ProofCarryingShowcasePlan plan,
        ProofCarryingShowcaseCandidateFreeze candidate,
        ProofCarryingShowcasePublicRandomnessReceipt randomness
    ) {
        String derived = ProofCarryingShowcaseSeedDeriver.derive(
            plan, candidate, randomness);
        Map<String, Object> payload = payload(
            SCHEMA,
            plan.showcaseId(),
            plan.contentHash(),
            candidate.contentHash(),
            randomness.contentHash(),
            randomness.chainHash(),
            randomness.round(),
            DERIVATION_ALGORITHM,
            derived,
            STATUS);
        return new ProofCarryingShowcaseSeedReceipt(
            SCHEMA,
            plan.showcaseId(),
            plan.contentHash(),
            candidate.contentHash(),
            randomness.contentHash(),
            randomness.chainHash(),
            randomness.round(),
            DERIVATION_ALGORITHM,
            derived,
            STATUS,
            ProofCarryingShowcaseJsonSupport.hashPayload(payload));
    }

    public static ProofCarryingShowcaseSeedReceipt fromCanonicalJson(
        String json
    ) {
        return ProofCarryingShowcaseJsonSupport.read(
            json,
            ProofCarryingShowcaseSeedReceipt.class,
            "showcase seed receipt");
    }

    public String toCanonicalJson() {
        return ProofCarryingShowcaseJsonSupport.toCanonicalJson(this);
    }

    public void requireCompatible(ProofCarryingShowcasePlan plan) {
        if (!showcaseId.equals(plan.showcaseId())
                || !planContentHash.equals(plan.contentHash())
                || !drandChainHash.equals(
                    plan.publicRandomness().chainHash())) {
            throw new IllegalArgumentException(
                "showcase seed receipt identity mismatch");
        }
    }

    public void requireCompatible(
        ProofCarryingShowcasePlan plan,
        ProofCarryingShowcaseCandidateFreeze candidate
    ) {
        requireCompatible(plan);
        candidate.requireCompatible(plan);
        if (!candidateFreezeContentHash.equals(
                candidate.contentHash())) {
            throw new IllegalArgumentException(
                "showcase seed receipt candidate mismatch");
        }
    }

    private static Map<String, Object> payload(
        String schema,
        String showcaseId,
        String planContentHash,
        String candidateFreezeContentHash,
        String randomnessReceiptContentHash,
        String drandChainHash,
        long drandRound,
        String derivationAlgorithm,
        String derivedSeed,
        String status
    ) {
        return ProofCarryingShowcaseJsonSupport.payload(
            "schema", schema,
            "showcaseId", showcaseId,
            "planContentHash", planContentHash,
            "candidateFreezeContentHash",
                candidateFreezeContentHash,
            "randomnessReceiptContentHash",
                randomnessReceiptContentHash,
            "drandChainHash", drandChainHash,
            "drandRound", drandRound,
            "derivationAlgorithm", derivationAlgorithm,
            "derivedSeed", derivedSeed,
            "status", status);
    }
}
