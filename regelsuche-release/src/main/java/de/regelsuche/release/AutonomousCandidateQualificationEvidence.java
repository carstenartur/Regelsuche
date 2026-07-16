package de.regelsuche.release;

import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2;
import de.regelsuche.json.JsonWriter;
import java.util.Comparator;
import java.util.List;

/** Release qualification evidence for the exact retained production candidate. */
public record AutonomousCandidateQualificationEvidence(
    String schema,
    String campaignManifestHash,
    String briefHash,
    String inventoryHash,
    String modelHash,
    String conjectureId,
    String candidateBranchId,
    String miningEvidenceHash,
    String lineageHash,
    List<String> supportingObservationIds,
    List<String> sourceObservationBranchHashes,
    String leftPattern,
    String rightPattern,
    List<String> parameterRelations,
    List<String> assumptions,
    String suiteRevision,
    String suiteHash,
    String splitAuditHash,
    String evaluationHash,
    String utilityHash,
    int heldOutFamilyOrClusterCount,
    int configuredPositiveHoldouts,
    int executedPositiveHoldouts,
    int configuredNegativeHoldouts,
    int executedNegativeHoldouts,
    int mandatorySkippedWorkCount,
    int refutingHoldouts,
    int counterexamplesFound,
    boolean pairedHeldOutUtilityEvaluated,
    int pairedUtilityPermille,
    int correctnessRegressionCount,
    boolean qualified,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.autonomous-candidate-qualification/v1";

    public AutonomousCandidateQualificationEvidence {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported candidate qualification schema");
        }
        for (String hash : List.of(
                campaignManifestHash, briefHash, inventoryHash, modelHash,
                miningEvidenceHash, lineageHash, suiteHash, splitAuditHash,
                evaluationHash, utilityHash, contentHash)) {
            requireSha(hash);
        }
        supportingObservationIds = sorted(supportingObservationIds);
        sourceObservationBranchHashes = sorted(sourceObservationBranchHashes);
        sourceObservationBranchHashes.forEach(
            AutonomousCandidateQualificationEvidence::requireSha);
        parameterRelations = sorted(parameterRelations);
        assumptions = sorted(assumptions);
        requireText(conjectureId, "conjectureId");
        requireText(candidateBranchId, "candidateBranchId");
        requireText(leftPattern, "leftPattern");
        requireText(rightPattern, "rightPattern");
        requireText(suiteRevision, "suiteRevision");
        for (int value : new int[] {
                heldOutFamilyOrClusterCount,
                configuredPositiveHoldouts,
                executedPositiveHoldouts,
                configuredNegativeHoldouts,
                executedNegativeHoldouts,
                mandatorySkippedWorkCount,
                refutingHoldouts,
                counterexamplesFound,
                pairedUtilityPermille,
                correctnessRegressionCount}) {
            if (value < 0) {
                throw new IllegalArgumentException(
                    "qualification evidence counts must be non-negative");
            }
        }
        if (executedPositiveHoldouts > configuredPositiveHoldouts
                || executedNegativeHoldouts > configuredNegativeHoldouts
                || pairedUtilityPermille > 1000
                || supportingObservationIds.isEmpty()
                || supportingObservationIds.size()
                    != sourceObservationBranchHashes.size()) {
            throw new IllegalArgumentException(
                "qualification evidence accounting or lineage is inconsistent");
        }
        boolean expectedQualified = heldOutFamilyOrClusterCount >= 1
            && configuredPositiveHoldouts >= 12
            && executedPositiveHoldouts == configuredPositiveHoldouts
            && configuredNegativeHoldouts >= 12
            && executedNegativeHoldouts == configuredNegativeHoldouts
            && mandatorySkippedWorkCount == 0
            && refutingHoldouts == 0
            && counterexamplesFound == 0
            && pairedHeldOutUtilityEvaluated
            && pairedUtilityPermille > 0
            && correctnessRegressionCount == 0;
        if (qualified != expectedQualified) {
            throw new IllegalArgumentException(
                "qualified status does not match retained qualification evidence");
        }
        String expectedHash = hash(
            schema, campaignManifestHash, briefHash, inventoryHash, modelHash,
            conjectureId, candidateBranchId, miningEvidenceHash, lineageHash,
            supportingObservationIds, sourceObservationBranchHashes,
            leftPattern, rightPattern, parameterRelations, assumptions,
            suiteRevision, suiteHash, splitAuditHash, evaluationHash, utilityHash,
            heldOutFamilyOrClusterCount,
            configuredPositiveHoldouts, executedPositiveHoldouts,
            configuredNegativeHoldouts, executedNegativeHoldouts,
            mandatorySkippedWorkCount, refutingHoldouts, counterexamplesFound,
            pairedHeldOutUtilityEvaluated, pairedUtilityPermille,
            correctnessRegressionCount, qualified);
        if (!expectedHash.equals(contentHash)) {
            throw new IllegalArgumentException(
                "candidate qualification hash does not match canonical fields");
        }
    }

    public static AutonomousCandidateQualificationEvidence create(
        String campaignManifestHash,
        String briefHash,
        String inventoryHash,
        String modelHash,
        String conjectureId,
        String candidateBranchId,
        String miningEvidenceHash,
        String lineageHash,
        List<String> supportingObservationIds,
        List<String> sourceObservationBranchHashes,
        String leftPattern,
        String rightPattern,
        List<String> parameterRelations,
        List<String> assumptions,
        String suiteRevision,
        String suiteHash,
        String splitAuditHash,
        String evaluationHash,
        String utilityHash,
        int heldOutFamilyOrClusterCount,
        int configuredPositiveHoldouts,
        int executedPositiveHoldouts,
        int configuredNegativeHoldouts,
        int executedNegativeHoldouts,
        int mandatorySkippedWorkCount,
        int refutingHoldouts,
        int counterexamplesFound,
        boolean pairedHeldOutUtilityEvaluated,
        int pairedUtilityPermille,
        int correctnessRegressionCount
    ) {
        List<String> observationIds = sorted(supportingObservationIds);
        List<String> branchHashes = sorted(sourceObservationBranchHashes);
        List<String> relations = sorted(parameterRelations);
        List<String> normalizedAssumptions = sorted(assumptions);
        boolean qualified = heldOutFamilyOrClusterCount >= 1
            && configuredPositiveHoldouts >= 12
            && executedPositiveHoldouts == configuredPositiveHoldouts
            && configuredNegativeHoldouts >= 12
            && executedNegativeHoldouts == configuredNegativeHoldouts
            && mandatorySkippedWorkCount == 0
            && refutingHoldouts == 0
            && counterexamplesFound == 0
            && pairedHeldOutUtilityEvaluated
            && pairedUtilityPermille > 0
            && correctnessRegressionCount == 0;
        String contentHash = hash(
            SCHEMA, campaignManifestHash, briefHash, inventoryHash, modelHash,
            conjectureId, candidateBranchId, miningEvidenceHash, lineageHash,
            observationIds, branchHashes, leftPattern, rightPattern, relations,
            normalizedAssumptions, suiteRevision, suiteHash, splitAuditHash,
            evaluationHash, utilityHash, heldOutFamilyOrClusterCount,
            configuredPositiveHoldouts, executedPositiveHoldouts,
            configuredNegativeHoldouts, executedNegativeHoldouts,
            mandatorySkippedWorkCount, refutingHoldouts, counterexamplesFound,
            pairedHeldOutUtilityEvaluated, pairedUtilityPermille,
            correctnessRegressionCount, qualified);
        return new AutonomousCandidateQualificationEvidence(
            SCHEMA, campaignManifestHash, briefHash, inventoryHash, modelHash,
            conjectureId, candidateBranchId, miningEvidenceHash, lineageHash,
            observationIds, branchHashes, leftPattern, rightPattern, relations,
            normalizedAssumptions, suiteRevision, suiteHash, splitAuditHash,
            evaluationHash, utilityHash, heldOutFamilyOrClusterCount,
            configuredPositiveHoldouts, executedPositiveHoldouts,
            configuredNegativeHoldouts, executedNegativeHoldouts,
            mandatorySkippedWorkCount, refutingHoldouts, counterexamplesFound,
            pairedHeldOutUtilityEvaluated, pairedUtilityPermille,
            correctnessRegressionCount, qualified, contentHash);
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("schema", schema)
            .property("campaignManifestHash", campaignManifestHash)
            .property("briefHash", briefHash)
            .property("inventoryHash", inventoryHash)
            .property("modelHash", modelHash)
            .property("conjectureId", conjectureId)
            .property("candidateBranchId", candidateBranchId)
            .property("miningEvidenceHash", miningEvidenceHash)
            .property("lineageHash", lineageHash)
            .stringArray("supportingObservationIds", supportingObservationIds)
            .stringArray("sourceObservationBranchHashes",
                sourceObservationBranchHashes)
            .property("leftPattern", leftPattern)
            .property("rightPattern", rightPattern)
            .stringArray("parameterRelations", parameterRelations)
            .stringArray("assumptions", assumptions)
            .property("suiteRevision", suiteRevision)
            .property("suiteHash", suiteHash)
            .property("splitAuditHash", splitAuditHash)
            .property("evaluationHash", evaluationHash)
            .property("utilityHash", utilityHash)
            .property("heldOutFamilyOrClusterCount",
                heldOutFamilyOrClusterCount)
            .property("configuredPositiveHoldouts", configuredPositiveHoldouts)
            .property("executedPositiveHoldouts", executedPositiveHoldouts)
            .property("configuredNegativeHoldouts", configuredNegativeHoldouts)
            .property("executedNegativeHoldouts", executedNegativeHoldouts)
            .property("mandatorySkippedWorkCount", mandatorySkippedWorkCount)
            .property("refutingHoldouts", refutingHoldouts)
            .property("counterexamplesFound", counterexamplesFound)
            .property("pairedHeldOutUtilityEvaluated",
                pairedHeldOutUtilityEvaluated)
            .property("pairedUtilityPermille", pairedUtilityPermille)
            .property("correctnessRegressionCount", correctnessRegressionCount)
            .property("qualified", qualified)
            .property("contentHash", contentHash)
            .endObject().toString();
    }

    private static String hash(
        String schema, String campaign, String brief, String inventory,
        String model, String conjecture, String branch, String mining,
        String lineage, List<String> observations, List<String> branchHashes,
        String left, String right, List<String> relations,
        List<String> assumptions, String revision, String suite,
        String split, String evaluation, String utility, int heldOut,
        int configuredPositive, int executedPositive, int configuredNegative,
        int executedNegative, int skipped, int refuting, int counterexamples,
        boolean utilityEvaluated, int utilityPermille, int regressions,
        boolean qualified
    ) {
        return AutonomousResearchBriefV2.hash(
            schema + "\ncampaign=" + campaign + "\nbrief=" + brief
                + "\ninventory=" + inventory + "\nmodel=" + model
                + "\nconjecture=" + conjecture + "\nbranch=" + branch
                + "\nmining=" + mining + "\nlineage=" + lineage
                + "\nobservations=" + observations
                + "\nbranchHashes=" + branchHashes
                + "\nrelation=" + left + "->" + right
                + "\nparameterRelations=" + relations
                + "\nassumptions=" + assumptions + "\nrevision=" + revision
                + "\nsuite=" + suite + "\nsplit=" + split
                + "\nevaluation=" + evaluation + "\nutility=" + utility
                + "\nheldOut=" + heldOut
                + "\npositive=" + executedPositive + '/' + configuredPositive
                + "\nnegative=" + executedNegative + '/' + configuredNegative
                + "\nskipped=" + skipped + "\nrefuting=" + refuting
                + "\ncounterexamples=" + counterexamples
                + "\nutilityEvaluated=" + utilityEvaluated
                + "\nutilityPermille=" + utilityPermille
                + "\nregressions=" + regressions
                + "\nqualified=" + qualified);
    }

    private static List<String> sorted(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct().sorted().toList();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireSha(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("value must be SHA-256");
        }
    }
}
