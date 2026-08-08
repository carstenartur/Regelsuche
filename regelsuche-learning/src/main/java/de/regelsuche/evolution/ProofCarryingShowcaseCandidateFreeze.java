package de.regelsuche.evolution;

import java.util.List;
import java.util.Map;

/**
 * Complete TRAIN-derived candidate freeze that must predate public randomness.
 */
public record ProofCarryingShowcaseCandidateFreeze(
    String schema,
    String showcaseId,
    String planContentHash,
    String repositoryCommit,
    String trainingRunHash,
    String selectionEvidenceHash,
    String candidateContentHash,
    String candidateAlphaStructuralHash,
    String humanReadableProgramHash,
    String primitiveInventoryHash,
    String workBudgetPolicyHash,
    String evaluationProtocolHash,
    List<String> seedCandidateHashes,
    int programNodeCount,
    boolean containsCompositionTopology,
    boolean containsDecisionTopology,
    int minimumDeclaredPrimitivePathSteps,
    long frozenAtUnixTime,
    long randomnessNotBeforeUnixTime,
    String status,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.proof-carrying-showcase-candidate-freeze/v1";
    public static final String STATUS =
        "CANDIDATE_FROZEN_FINAL_TEST_UNSEEN";

    public ProofCarryingShowcaseCandidateFreeze {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported showcase candidate-freeze schema");
        }
        showcaseId = ProofCarryingShowcaseJsonSupport.requireText(
            showcaseId, "showcaseId");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            planContentHash, "planContentHash");
        ProofCarryingShowcaseJsonSupport.requireCommit(
            repositoryCommit, "repositoryCommit");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            trainingRunHash, "trainingRunHash");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            selectionEvidenceHash, "selectionEvidenceHash");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            candidateContentHash, "candidateContentHash");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            candidateAlphaStructuralHash,
            "candidateAlphaStructuralHash");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            humanReadableProgramHash,
            "humanReadableProgramHash");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            primitiveInventoryHash, "primitiveInventoryHash");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            workBudgetPolicyHash, "workBudgetPolicyHash");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            evaluationProtocolHash, "evaluationProtocolHash");
        seedCandidateHashes = ProofCarryingShowcaseJsonSupport
            .immutableHashes(
                seedCandidateHashes,
                "seedCandidateHashes",
                true);
        if (seedCandidateHashes.contains(candidateContentHash)) {
            throw new IllegalArgumentException(
                "frozen candidate must not equal a seed candidate");
        }
        if (programNodeCount < 1
                || !containsCompositionTopology
                || !containsDecisionTopology
                || minimumDeclaredPrimitivePathSteps < 3) {
            throw new IllegalArgumentException(
                "frozen candidate lacks required showcase structure");
        }
        if (frozenAtUnixTime < 1
                || randomnessNotBeforeUnixTime <= frozenAtUnixTime) {
            throw new IllegalArgumentException(
                "candidate freeze has an invalid randomness boundary");
        }
        if (!STATUS.equals(status)) {
            throw new IllegalArgumentException(
                "candidate freeze must keep FINAL TEST unseen");
        }
        ProofCarryingShowcaseJsonSupport.requireSha256(
            contentHash, "contentHash");
        String expected = ProofCarryingShowcaseJsonSupport.hashPayload(
            payload(
                schema,
                showcaseId,
                planContentHash,
                repositoryCommit,
                trainingRunHash,
                selectionEvidenceHash,
                candidateContentHash,
                candidateAlphaStructuralHash,
                humanReadableProgramHash,
                primitiveInventoryHash,
                workBudgetPolicyHash,
                evaluationProtocolHash,
                seedCandidateHashes,
                programNodeCount,
                containsCompositionTopology,
                containsDecisionTopology,
                minimumDeclaredPrimitivePathSteps,
                frozenAtUnixTime,
                randomnessNotBeforeUnixTime,
                status));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "showcase candidate-freeze contentHash mismatch");
        }
    }

    public static ProofCarryingShowcaseCandidateFreeze create(
        ProofCarryingShowcasePlan plan,
        String repositoryCommit,
        String trainingRunHash,
        String selectionEvidenceHash,
        String candidateContentHash,
        String candidateAlphaStructuralHash,
        String humanReadableProgramHash,
        String primitiveInventoryHash,
        String workBudgetPolicyHash,
        String evaluationProtocolHash,
        List<String> seedCandidateHashes,
        int programNodeCount,
        boolean containsCompositionTopology,
        boolean containsDecisionTopology,
        int minimumDeclaredPrimitivePathSteps,
        long frozenAtUnixTime
    ) {
        long notBefore;
        try {
            notBefore = Math.addExact(
                frozenAtUnixTime,
                plan.publicRandomness()
                    .minimumDelaySecondsAfterCandidateFreeze());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                "candidate freeze time overflows randomness boundary",
                exception);
        }
        List<String> seeds = ProofCarryingShowcaseJsonSupport
            .immutableHashes(
                seedCandidateHashes,
                "seedCandidateHashes",
                true);
        Map<String, Object> payload = payload(
            SCHEMA,
            plan.showcaseId(),
            plan.contentHash(),
            repositoryCommit,
            trainingRunHash,
            selectionEvidenceHash,
            candidateContentHash,
            candidateAlphaStructuralHash,
            humanReadableProgramHash,
            primitiveInventoryHash,
            workBudgetPolicyHash,
            evaluationProtocolHash,
            seeds,
            programNodeCount,
            containsCompositionTopology,
            containsDecisionTopology,
            minimumDeclaredPrimitivePathSteps,
            frozenAtUnixTime,
            notBefore,
            STATUS);
        return new ProofCarryingShowcaseCandidateFreeze(
            SCHEMA,
            plan.showcaseId(),
            plan.contentHash(),
            repositoryCommit,
            trainingRunHash,
            selectionEvidenceHash,
            candidateContentHash,
            candidateAlphaStructuralHash,
            humanReadableProgramHash,
            primitiveInventoryHash,
            workBudgetPolicyHash,
            evaluationProtocolHash,
            seeds,
            programNodeCount,
            containsCompositionTopology,
            containsDecisionTopology,
            minimumDeclaredPrimitivePathSteps,
            frozenAtUnixTime,
            notBefore,
            STATUS,
            ProofCarryingShowcaseJsonSupport.hashPayload(payload));
    }

    public static ProofCarryingShowcaseCandidateFreeze fromCanonicalJson(
        String json
    ) {
        return ProofCarryingShowcaseJsonSupport.read(
            json,
            ProofCarryingShowcaseCandidateFreeze.class,
            "showcase candidate freeze");
    }

    public String toCanonicalJson() {
        return ProofCarryingShowcaseJsonSupport.toCanonicalJson(this);
    }

    public void requireCompatible(ProofCarryingShowcasePlan plan) {
        if (!showcaseId.equals(plan.showcaseId())
                || !planContentHash.equals(plan.contentHash())) {
            throw new IllegalArgumentException(
                "candidate freeze is not bound to the frozen plan");
        }
        int requiredSteps = plan.candidateFormation()
            .requiredCandidateProperties()
            .minimumPrimitiveStepsOnSuccessfulPath();
        if (minimumDeclaredPrimitivePathSteps < requiredSteps) {
            throw new IllegalArgumentException(
                "candidate primitive-path floor is below the plan");
        }
        long minimumNotBefore;
        try {
            minimumNotBefore = Math.addExact(
                frozenAtUnixTime,
                plan.publicRandomness()
                    .minimumDelaySecondsAfterCandidateFreeze());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                "candidate freeze time overflows randomness boundary",
                exception);
        }
        if (randomnessNotBeforeUnixTime < minimumNotBefore) {
            throw new IllegalArgumentException(
                "candidate randomness boundary violates the plan delay");
        }
    }

    private static Map<String, Object> payload(
        String schema,
        String showcaseId,
        String planContentHash,
        String repositoryCommit,
        String trainingRunHash,
        String selectionEvidenceHash,
        String candidateContentHash,
        String candidateAlphaStructuralHash,
        String humanReadableProgramHash,
        String primitiveInventoryHash,
        String workBudgetPolicyHash,
        String evaluationProtocolHash,
        List<String> seedCandidateHashes,
        int programNodeCount,
        boolean containsCompositionTopology,
        boolean containsDecisionTopology,
        int minimumDeclaredPrimitivePathSteps,
        long frozenAtUnixTime,
        long randomnessNotBeforeUnixTime,
        String status
    ) {
        return ProofCarryingShowcaseJsonSupport.payload(
            "schema", schema,
            "showcaseId", showcaseId,
            "planContentHash", planContentHash,
            "repositoryCommit", repositoryCommit,
            "trainingRunHash", trainingRunHash,
            "selectionEvidenceHash", selectionEvidenceHash,
            "candidateContentHash", candidateContentHash,
            "candidateAlphaStructuralHash",
                candidateAlphaStructuralHash,
            "humanReadableProgramHash", humanReadableProgramHash,
            "primitiveInventoryHash", primitiveInventoryHash,
            "workBudgetPolicyHash", workBudgetPolicyHash,
            "evaluationProtocolHash", evaluationProtocolHash,
            "seedCandidateHashes", seedCandidateHashes,
            "programNodeCount", programNodeCount,
            "containsCompositionTopology",
                containsCompositionTopology,
            "containsDecisionTopology", containsDecisionTopology,
            "minimumDeclaredPrimitivePathSteps",
                minimumDeclaredPrimitivePathSteps,
            "frozenAtUnixTime", frozenAtUnixTime,
            "randomnessNotBeforeUnixTime",
                randomnessNotBeforeUnixTime,
            "status", status);
    }
}
