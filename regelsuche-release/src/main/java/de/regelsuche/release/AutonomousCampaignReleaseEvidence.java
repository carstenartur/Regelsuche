package de.regelsuche.release;

import de.regelsuche.experiments.autopilot.AutonomousCandidateLifecycleV2.LifecycleOutcome;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.CandidateOutput;
import de.regelsuche.experiments.autopilot.AutonomousProductionCampaignRunner.CampaignRun;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import java.util.List;
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
        if (cleanRunManifestHashes.isEmpty()
                || cleanRunCount != cleanRunManifestHashes.size()
                || !cleanRunManifestHashes.contains(campaignManifestHash)) {
            throw new IllegalArgumentException(
                "clean runs must retain the campaign manifest and match their count");
        }
        boolean calculatedIdentical = cleanRunManifestHashes.stream()
            .distinct().count() == 1L;
        if (cleanRunsIdentical != calculatedIdentical) {
            throw new IllegalArgumentException(
                "cleanRunsIdentical must match the retained manifest hashes");
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
        if (!pairedHeldOutUtilityEvaluated && pairedUtilityPermille != 0) {
            throw new IllegalArgumentException(
                "unevaluated paired utility cannot report a gain");
        }
        requireText(projectNoveltyStatus, "projectNoveltyStatus");
        requireText(externalNoveltyStatus, "externalNoveltyStatus");
        requireText(symbolicProofStatus, "symbolicProofStatus");
        requireText(formalProofStatus, "formalProofStatus");
        requireSha256(evidenceHash, "evidenceHash");
        String expectedHash = canonicalHash(
            schema,
            campaignManifestHash,
            cleanRunManifestHashes,
            cleanRunCount,
            cleanRunsIdentical,
            targetFree,
            seedFamilyCount,
            observationCount,
            candidateCount,
            rejectedClusterCount,
            alphaDistinctSupport,
            aggregateMiningComplete,
            exactSupportingLineage,
            mandatorySkippedWorkCount,
            heldOutFamilyOrClusterCount,
            configuredPositiveHoldouts,
            executedPositiveHoldouts,
            configuredNegativeHoldouts,
            executedNegativeHoldouts,
            refutingHoldouts,
            counterexampleStrategyCount,
            counterexamplesFound,
            projectNoveltyStatus,
            externalNoveltyStatus,
            symbolicProofStatus,
            formalProofStatus,
            unresolvedAssumptionCount,
            lifecycleHandoffComplete,
            pairedHeldOutUtilityEvaluated,
            pairedUtilityPermille,
            hiddenReferenceIsolated,
            hiddenRuleBenchmarkComplete,
            executableRediscoveryRetained,
            publicEvidenceReviewed);
        if (!expectedHash.equals(evidenceHash)) {
            throw new IllegalArgumentException(
                "release evidence hash does not match canonical fields");
        }
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

        String campaignHash = run.contentHash();
        boolean targetFree = !run.targetProvided();
        int seedFamilies = run.seedFamilyCount();
        int observations = run.observationCount();
        int candidates = run.candidateCount();
        int rejectedClusters = run.rejectedClusterCount();
        int alphaSupport = conjecture.distinctAlphaSupport();
        boolean aggregateComplete = run.lifecycle().mining().fullBatch()
            .binding().receipt().outputs().size() == 1;
        boolean lineageExact = exactLineage(output, conjecture);
        int heldOutFamilies = 0;
        String projectNovelty = run.lifecycle().novelty().status().name();
        String externalNovelty = run.lifecycle().novelty().externalNoveltyStatus();
        String symbolicProof = run.lifecycle().proof().proofStatus().name();
        String formalProof = run.lifecycle().proof().formalProofStatus();
        int unresolvedAssumptions = inferredAssumptions.size();
        boolean lifecycleComplete = run.lifecycle().lifecycleDecision().outcome()
            == LifecycleOutcome.COMPLETED;
        boolean pairedUtilityEvaluated = false;
        int pairedUtility = 0;
        boolean hiddenReferenceIsolated = false;
        boolean hiddenBenchmarkComplete = false;
        boolean executableRediscovery = false;
        boolean publicEvidenceReviewed = false;

        String evidenceHash = canonicalHash(
            SCHEMA,
            campaignHash,
            runHashes,
            runs.size(),
            identicalRuns,
            targetFree,
            seedFamilies,
            observations,
            candidates,
            rejectedClusters,
            alphaSupport,
            aggregateComplete,
            lineageExact,
            skippedMandatory,
            heldOutFamilies,
            configuredPositive,
            executedPositive,
            configuredNegative,
            executedNegative,
            refutingHoldouts,
            counterexampleSources.size(),
            counterexamples,
            projectNovelty,
            externalNovelty,
            symbolicProof,
            formalProof,
            unresolvedAssumptions,
            lifecycleComplete,
            pairedUtilityEvaluated,
            pairedUtility,
            hiddenReferenceIsolated,
            hiddenBenchmarkComplete,
            executableRediscovery,
            publicEvidenceReviewed);

        return new AutonomousCampaignReleaseEvidence(
            SCHEMA,
            campaignHash,
            runHashes,
            runs.size(),
            identicalRuns,
            targetFree,
            seedFamilies,
            observations,
            candidates,
            rejectedClusters,
            alphaSupport,
            aggregateComplete,
            lineageExact,
            skippedMandatory,
            heldOutFamilies,
            configuredPositive,
            executedPositive,
            configuredNegative,
            executedNegative,
            refutingHoldouts,
            counterexampleSources.size(),
            counterexamples,
            projectNovelty,
            externalNovelty,
            symbolicProof,
            formalProof,
            unresolvedAssumptions,
            lifecycleComplete,
            pairedUtilityEvaluated,
            pairedUtility,
            hiddenReferenceIsolated,
            hiddenBenchmarkComplete,
            executableRediscovery,
            publicEvidenceReviewed,
            evidenceHash);
    }

    public int configuredFreshHoldouts() {
        return Math.addExact(configuredPositiveHoldouts, configuredNegativeHoldouts);
    }

    public int executedFreshHoldouts() {
        return Math.addExact(executedPositiveHoldouts, executedNegativeHoldouts);
    }

    private static String canonicalHash(
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
        boolean publicEvidenceReviewed
    ) {
        String material = schema
            + "\ncampaignManifestHash=" + campaignManifestHash
            + "\ncleanRunManifestHashes=" + cleanRunManifestHashes
            + "\ncleanRunCount=" + cleanRunCount
            + "\ncleanRunsIdentical=" + cleanRunsIdentical
            + "\ntargetFree=" + targetFree
            + "\nseedFamilyCount=" + seedFamilyCount
            + "\nobservationCount=" + observationCount
            + "\ncandidateCount=" + candidateCount
            + "\nrejectedClusterCount=" + rejectedClusterCount
            + "\nalphaDistinctSupport=" + alphaDistinctSupport
            + "\naggregateMiningComplete=" + aggregateMiningComplete
            + "\nexactSupportingLineage=" + exactSupportingLineage
            + "\nmandatorySkippedWorkCount=" + mandatorySkippedWorkCount
            + "\nheldOutFamilyOrClusterCount=" + heldOutFamilyOrClusterCount
            + "\nconfiguredPositiveHoldouts=" + configuredPositiveHoldouts
            + "\nexecutedPositiveHoldouts=" + executedPositiveHoldouts
            + "\nconfiguredNegativeHoldouts=" + configuredNegativeHoldouts
            + "\nexecutedNegativeHoldouts=" + executedNegativeHoldouts
            + "\nrefutingHoldouts=" + refutingHoldouts
            + "\ncounterexampleStrategyCount=" + counterexampleStrategyCount
            + "\ncounterexamplesFound=" + counterexamplesFound
            + "\nprojectNoveltyStatus=" + projectNoveltyStatus
            + "\nexternalNoveltyStatus=" + externalNoveltyStatus
            + "\nsymbolicProofStatus=" + symbolicProofStatus
            + "\nformalProofStatus=" + formalProofStatus
            + "\nunresolvedAssumptionCount=" + unresolvedAssumptionCount
            + "\nlifecycleHandoffComplete=" + lifecycleHandoffComplete
            + "\npairedHeldOutUtilityEvaluated=" + pairedHeldOutUtilityEvaluated
            + "\npairedUtilityPermille=" + pairedUtilityPermille
            + "\nhiddenReferenceIsolated=" + hiddenReferenceIsolated
            + "\nhiddenRuleBenchmarkComplete=" + hiddenRuleBenchmarkComplete
            + "\nexecutableRediscoveryRetained=" + executableRediscoveryRetained
            + "\npublicEvidenceReviewed=" + publicEvidenceReviewed;
        return AutonomousResearchBriefV2.hash(material);
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
