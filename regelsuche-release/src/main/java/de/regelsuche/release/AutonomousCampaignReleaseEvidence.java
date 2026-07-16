package de.regelsuche.release;

import de.regelsuche.experiments.autopilot.AutonomousCandidateLifecycleV2.LifecycleOutcome;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.CandidateOutput;
import de.regelsuche.experiments.autopilot.AutonomousProductionCampaignRunner.CampaignRun;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Factual release-gate inputs derived from complete campaign artifacts. */
public record AutonomousCampaignReleaseEvidence(
    String schema,
    String campaignManifestHash,
    List<String> cleanRunManifestHashes,
    int cleanRunCount,
    boolean cleanRunsIdentical,
    boolean targetFree,
    int seedFamilyCount,
    int observationCount,
    int candidateCount,
    int rejectedClusterCount,
    int alphaDistinctSupport,
    boolean aggregateMiningComplete,
    boolean exactSupportingLineage,
    int mandatorySkippedWorkCount,
    int heldOutFamilyOrClusterCount,
    int configuredPositiveHoldouts,
    int executedPositiveHoldouts,
    int configuredNegativeHoldouts,
    int executedNegativeHoldouts,
    int refutingHoldouts,
    int counterexampleStrategyCount,
    int counterexamplesFound,
    String projectNoveltyStatus,
    String externalNoveltyStatus,
    String symbolicProofStatus,
    String formalProofStatus,
    int unresolvedAssumptionCount,
    boolean lifecycleHandoffComplete,
    boolean pairedHeldOutUtilityEvaluated,
    int pairedUtilityPermille,
    boolean hiddenReferenceIsolated,
    boolean hiddenRuleBenchmarkComplete,
    boolean executableRediscoveryRetained,
    boolean publicEvidenceReviewed,
    String evidenceHash
) {
    public static final String SCHEMA =
        "regelsuche.autonomous-campaign-release-evidence/v1";

    public AutonomousCampaignReleaseEvidence {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported release evidence schema");
        }
        requireSha256(campaignManifestHash, "campaignManifestHash");
        cleanRunManifestHashes = cleanRunManifestHashes == null
            ? List.of()
            : cleanRunManifestHashes.stream().sorted().toList();
        cleanRunManifestHashes.forEach(hash -> requireSha256(
            hash, "cleanRunManifestHashes"));
        if (cleanRunCount != cleanRunManifestHashes.size()) {
            throw new IllegalArgumentException(
                "cleanRunCount must match retained run hashes");
        }
        for (int value : List.of(
                seedFamilyCount,
                observationCount,
                candidateCount,
                rejectedClusterCount,
                alphaDistinctSupport,
                mandatorySkippedWorkCount,
                heldOutFamilyOrClusterCount,
                configuredPositiveHoldouts,
                executedPositiveHoldouts,
                configuredNegativeHoldouts,
                executedNegativeHoldouts,
                refutingHoldouts,
                counterexampleStrategyCount,
                counterexamplesFound,
                unresolvedAssumptionCount,
                pairedUtilityPermille)) {
            if (value < 0) {
                throw new IllegalArgumentException(
                    "release evidence counts must be non-negative");
            }
        }
        if (executedPositiveHoldouts > configuredPositiveHoldouts
                || executedNegativeHoldouts > configuredNegativeHoldouts) {
            throw new IllegalArgumentException(
                "executed holdouts cannot exceed configured holdouts");
        }
        requireText(projectNoveltyStatus, "projectNoveltyStatus");
        requireText(externalNoveltyStatus, "externalNoveltyStatus");
        requireText(symbolicProofStatus, "symbolicProofStatus");
        requireText(formalProofStatus, "formalProofStatus");
        requireSha256(evidenceHash, "evidenceHash");
    }

    public static AutonomousCampaignReleaseEvidence from(
        List<CampaignRun> suppliedRuns
    ) {
        if (suppliedRuns == null || suppliedRuns.isEmpty()) {
            throw new IllegalArgumentException(
                "release evidence requires at least one complete campaign run");
        }
        List<CampaignRun> runs = List.copyOf(suppliedRuns);
        CampaignRun run = runs.getFirst();
        OpenTargetConjecture conjecture = retainedConjecture(run);
        CandidateOutput output = retainedOutput(run, conjecture);
        List<String> runHashes = runs.stream()
            .map(CampaignRun::contentHash)
            .sorted()
            .toList();
        boolean identicalRuns = runHashes.stream().distinct().count() == 1L;
        var evaluation = run.lifecycle().evaluation();
        int configuredPositive = evaluation.configuredPositiveHoldouts();
        int executedPositive = evaluation.executedPositiveHoldouts();
        int configuredNegative = evaluation.configuredNegativeHoldouts();
        int executedNegative = evaluation.executedNegativeHoldouts();
        int refutingHoldouts = Math.addExact(
            (int) evaluation.positiveResults().stream()
                .filter(result -> !result.passed()).count(),
            (int) evaluation.negativeResults().stream()
                .filter(result -> !result.passed()).count());
        int skippedMandatory = Math.addExact(
            Math.toIntExact(run.lifecycle().mining().generation().receipt()
                .skippedResources().getOrDefault(ResourceKind.OBSERVATIONS, 0L)),
            Math.toIntExact(run.lifecycle().mining().formationReceipt()
                .skippedResources().getOrDefault(ResourceKind.MINING_BATCHES, 0L)));
        skippedMandatory = Math.addExact(
            skippedMandatory,
            Math.toIntExact(run.lifecycle().stageLedger().receipts().stream()
                .mapToLong(receipt -> receipt.skipped()).sum()));
        int counterexamples = "COUNTEREXAMPLE_FOUND".equals(
            evaluation.counterexample().status()) ? 1 : 0;
        List<String> counterexampleSources = evaluation.counterexample()
            .attemptedSources().stream().distinct().sorted().toList();
        List<String> inferredAssumptions = evaluation.counterexample()
            .inferredAssumptions().stream().distinct().sorted().toList();
        String evidenceMaterial = SCHEMA
            + "\ncampaign=" + run.contentHash()
            + "\nruns=" + runHashes
            + "\ntargetFree=" + !run.targetProvided()
            + "\nfamilies=" + run.seedFamilyCount()
            + "\nobservations=" + run.observationCount()
            + "\ncandidates=" + run.candidateCount()
            + "\nrejected=" + run.rejectedClusterCount()
            + "\nalphaSupport=" + conjecture.distinctAlphaSupport()
            + "\nlineage=" + exactLineage(output, conjecture)
            + "\nmandatorySkipped=" + skippedMandatory
            + "\npositiveHoldouts=" + executedPositive + '/' + configuredPositive
            + "\nnegativeHoldouts=" + executedNegative + '/' + configuredNegative
            + "\nrefutingHoldouts=" + refutingHoldouts
            + "\ncounterexampleStrategies=" + counterexampleSources
            + "\ncounterexamples=" + counterexamples
            + "\nprojectNovelty=" + run.lifecycle().novelty().status().name()
            + "\nexternalNovelty="
                + run.lifecycle().novelty().externalNoveltyStatus()
            + "\nsymbolicProof=" + run.lifecycle().proof().proofStatus().name()
            + "\nformalProof=" + run.lifecycle().proof().formalProofStatus()
            + "\nunresolvedAssumptions=" + inferredAssumptions
            + "\nlifecycle=" + run.lifecycle().lifecycleDecision().outcome().name();
        return new AutonomousCampaignReleaseEvidence(
            SCHEMA,
            run.contentHash(),
            runHashes,
            runs.size(),
            identicalRuns,
            !run.targetProvided(),
            run.seedFamilyCount(),
            run.observationCount(),
            run.candidateCount(),
            run.rejectedClusterCount(),
            conjecture.distinctAlphaSupport(),
            run.lifecycle().mining().fullBatch().binding().receipt()
                .outputs().size() == 1,
            exactLineage(output, conjecture),
            skippedMandatory,
            0,
            configuredPositive,
            executedPositive,
            configuredNegative,
            executedNegative,
            refutingHoldouts,
            counterexampleSources.size(),
            counterexamples,
            run.lifecycle().novelty().status().name(),
            run.lifecycle().novelty().externalNoveltyStatus(),
            run.lifecycle().proof().proofStatus().name(),
            run.lifecycle().proof().formalProofStatus(),
            inferredAssumptions.size(),
            run.lifecycle().lifecycleDecision().outcome()
                == LifecycleOutcome.COMPLETED,
            false,
            0,
            false,
            false,
            false,
            false,
            de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.hash(
                evidenceMaterial));
    }

    public int configuredFreshHoldouts() {
        return Math.addExact(configuredPositiveHoldouts, configuredNegativeHoldouts);
    }

    public int executedFreshHoldouts() {
        return Math.addExact(executedPositiveHoldouts, executedNegativeHoldouts);
    }

    private static OpenTargetConjecture retainedConjecture(CampaignRun run) {
        List<OpenTargetConjecture> conjectures = run.lifecycle().mining()
            .fullBatch().evidence().report().conjectures();
        if (conjectures.size() != 1) {
            throw new IllegalArgumentException(
                "release evidence requires exactly one retained conjecture");
        }
        return conjectures.getFirst();
    }

    private static CandidateOutput retainedOutput(
        CampaignRun run,
        OpenTargetConjecture conjecture
    ) {
        List<CandidateOutput> outputs = run.lifecycle().mining().fullBatch()
            .binding().receipt().outputs();
        if (outputs.size() != 1
                || !outputs.getFirst().conjectureId()
                    .equals(conjecture.conjectureId())) {
            throw new IllegalArgumentException(
                "release evidence candidate output is not linked to its conjecture");
        }
        return outputs.getFirst();
    }

    private static boolean exactLineage(
        CandidateOutput output,
        OpenTargetConjecture conjecture
    ) {
        Set<String> declared = new TreeSet<>(
            conjecture.supportingObservationIds());
        Set<String> linked = output.sources().stream()
            .map(source -> source.observationId())
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        return declared.equals(linked)
            && linked.size() == conjecture.supportCount()
            && output.sources().stream().allMatch(source ->
                isSha256(source.snapshotHash())
                    && isSha256(source.evidenceHash())
                    && isSha256(source.observationBranchHash()));
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("schema", schema)
            .property("campaignManifestHash", campaignManifestHash)
            .stringArray("cleanRunManifestHashes", cleanRunManifestHashes)
            .property("cleanRunCount", cleanRunCount)
            .property("cleanRunsIdentical", cleanRunsIdentical)
            .property("targetFree", targetFree)
            .property("seedFamilyCount", seedFamilyCount)
            .property("observationCount", observationCount)
            .property("candidateCount", candidateCount)
            .property("rejectedClusterCount", rejectedClusterCount)
            .property("alphaDistinctSupport", alphaDistinctSupport)
            .property("aggregateMiningComplete", aggregateMiningComplete)
            .property("exactSupportingLineage", exactSupportingLineage)
            .property("mandatorySkippedWorkCount", mandatorySkippedWorkCount)
            .property("heldOutFamilyOrClusterCount", heldOutFamilyOrClusterCount)
            .property("configuredPositiveHoldouts", configuredPositiveHoldouts)
            .property("executedPositiveHoldouts", executedPositiveHoldouts)
            .property("configuredNegativeHoldouts", configuredNegativeHoldouts)
            .property("executedNegativeHoldouts", executedNegativeHoldouts)
            .property("refutingHoldouts", refutingHoldouts)
            .property("counterexampleStrategyCount", counterexampleStrategyCount)
            .property("counterexamplesFound", counterexamplesFound)
            .property("projectNoveltyStatus", projectNoveltyStatus)
            .property("externalNoveltyStatus", externalNoveltyStatus)
            .property("symbolicProofStatus", symbolicProofStatus)
            .property("formalProofStatus", formalProofStatus)
            .property("unresolvedAssumptionCount", unresolvedAssumptionCount)
            .property("lifecycleHandoffComplete", lifecycleHandoffComplete)
            .property("pairedHeldOutUtilityEvaluated",
                pairedHeldOutUtilityEvaluated)
            .property("pairedUtilityPermille", pairedUtilityPermille)
            .property("hiddenReferenceIsolated", hiddenReferenceIsolated)
            .property("hiddenRuleBenchmarkComplete", hiddenRuleBenchmarkComplete)
            .property("executableRediscoveryRetained", executableRediscoveryRetained)
            .property("publicEvidenceReviewed", publicEvidenceReviewed)
            .property("evidenceHash", evidenceHash)
            .endObject()
            .toString();
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }

    private static void requireSha256(String value, String name) {
        if (!isSha256(value)) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
