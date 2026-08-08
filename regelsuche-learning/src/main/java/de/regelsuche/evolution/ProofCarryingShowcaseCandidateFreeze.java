package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Complete learned-program freeze that must precede public randomness.
 *
 * <p>The artifact binds only TRAIN-derived state and structural facts. Its
 * not-before timestamp is fixed at creation and no randomness round, generated
 * FINAL TEST case or later-stage outcome is represented.</p>
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
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    public ProofCarryingShowcaseCandidateFreeze {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported showcase candidate-freeze schema");
        }
        requireText(showcaseId, "showcaseId");
        EvolutionGenome.requireSha256(planContentHash, "planContentHash");
        if (repositoryCommit == null
                || !COMMIT.matcher(repositoryCommit).matches()) {
            throw new IllegalArgumentException(
                "repositoryCommit must be a lowercase 40-character commit");
        }
        EvolutionGenome.requireSha256(trainingRunHash, "trainingRunHash");
        EvolutionGenome.requireSha256(
            selectionEvidenceHash, "selectionEvidenceHash");
        EvolutionGenome.requireSha256(
            candidateContentHash, "candidateContentHash");
        EvolutionGenome.requireSha256(
            candidateAlphaStructuralHash,
            "candidateAlphaStructuralHash");
        EvolutionGenome.requireSha256(
            humanReadableProgramHash, "humanReadableProgramHash");
        EvolutionGenome.requireSha256(
            primitiveInventoryHash, "primitiveInventoryHash");
        EvolutionGenome.requireSha256(
            workBudgetPolicyHash, "workBudgetPolicyHash");
        EvolutionGenome.requireSha256(
            evaluationProtocolHash, "evaluationProtocolHash");
        seedCandidateHashes = canonicalHashes(seedCandidateHashes);
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
                "candidate freeze requires a later randomness boundary");
        }
        if (!STATUS.equals(status)) {
            throw new IllegalArgumentException(
                "candidate freeze must keep FINAL TEST unseen");
        }
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
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
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "showcase candidate-freeze contentHash mismatch");
        }
    }

    static ProofCarryingShowcaseCandidateFreeze create(
        String showcaseId,
        String planContentHash,
        String repositoryCommit,
        String trainingRunHash,
        ProofCarryingShowcaseCandidateSelection selection,
        RetainedEvolutionRewriteProgramPopulationRun.RetainedCandidate candidate,
        String primitiveInventoryHash,
        String workBudgetPolicyHash,
        String evaluationProtocolHash,
        List<String> seedCandidateHashes,
        ProofCarryingShowcaseCandidateFreezer.ProgramFacts facts,
        long frozenAtUnixTime,
        long randomnessNotBeforeUnixTime
    ) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(facts, "facts");
        if (!selection.selectedCandidateHash().equals(
                candidate.candidateHash())
                || !selection.selectedCandidateAlphaStructuralHash().equals(
                    candidate.alphaStructuralHash())) {
            throw new IllegalArgumentException(
                "candidate payload differs from frozen selection");
        }
        String hash = EvolutionGenome.hash(render(
            showcaseId,
            planContentHash,
            repositoryCommit,
            trainingRunHash,
            selection.contentHash(),
            candidate.candidateHash(),
            candidate.alphaStructuralHash(),
            candidate.humanReadableProgramHash(),
            primitiveInventoryHash,
            workBudgetPolicyHash,
            evaluationProtocolHash,
            canonicalHashes(seedCandidateHashes),
            facts.nodeCount(),
            facts.containsCompositionTopology(),
            facts.containsDecisionTopology(),
            facts.minimumStructuralPrimitivePathSteps(),
            frozenAtUnixTime,
            randomnessNotBeforeUnixTime,
            null));
        return new ProofCarryingShowcaseCandidateFreeze(
            SCHEMA,
            showcaseId,
            planContentHash,
            repositoryCommit,
            trainingRunHash,
            selection.contentHash(),
            candidate.candidateHash(),
            candidate.alphaStructuralHash(),
            candidate.humanReadableProgramHash(),
            primitiveInventoryHash,
            workBudgetPolicyHash,
            evaluationProtocolHash,
            seedCandidateHashes,
            facts.nodeCount(),
            facts.containsCompositionTopology(),
            facts.containsDecisionTopology(),
            facts.minimumStructuralPrimitivePathSteps(),
            frozenAtUnixTime,
            randomnessNotBeforeUnixTime,
            STATUS,
            hash);
    }

    public String toCanonicalJson() {
        return render(
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
            contentHash);
    }

    private static List<String> canonicalHashes(List<String> values) {
        Objects.requireNonNull(values, "seedCandidateHashes");
        List<String> result = values.stream()
            .map(value -> {
                EvolutionGenome.requireSha256(
                    value, "seedCandidateHash");
                return value;
            })
            .distinct()
            .sorted(Comparator.naturalOrder())
            .toList();
        if (result.isEmpty()
                || new HashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException(
                "candidate freeze requires unique seed candidates");
        }
        return List.copyOf(result);
    }

    private static String render(
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
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("showcaseId", showcaseId)
            .property("planContentHash", planContentHash)
            .property("repositoryCommit", repositoryCommit)
            .property("trainingRunHash", trainingRunHash)
            .property("selectionEvidenceHash", selectionEvidenceHash)
            .property("candidateContentHash", candidateContentHash)
            .property(
                "candidateAlphaStructuralHash",
                candidateAlphaStructuralHash)
            .property(
                "humanReadableProgramHash", humanReadableProgramHash)
            .property("primitiveInventoryHash", primitiveInventoryHash)
            .property("workBudgetPolicyHash", workBudgetPolicyHash)
            .property("evaluationProtocolHash", evaluationProtocolHash)
            .stringArray("seedCandidateHashes", seedCandidateHashes)
            .property("programNodeCount", programNodeCount)
            .property(
                "containsCompositionTopology", containsCompositionTopology)
            .property(
                "containsDecisionTopology", containsDecisionTopology)
            .property(
                "minimumDeclaredPrimitivePathSteps",
                minimumDeclaredPrimitivePathSteps)
            .property("frozenAtUnixTime", frozenAtUnixTime)
            .property(
                "randomnessNotBeforeUnixTime",
                randomnessNotBeforeUnixTime)
            .property("status", STATUS);
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
