package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical pre-execution receipt binding every flagship input while proving
 * that TRAIN, VALIDATION and FINAL TEST result roots are still absent.
 */
public record EvolutionRewriteProgramFreezeReceipt(
    String schema,
    String freezeId,
    String repositoryCommit,
    DirtyStatePolicy dirtyStatePolicy,
    String splitManifestHash,
    String trainSuiteHash,
    String validationCommitmentHash,
    String finalTestCommitmentHash,
    String evaluationProtocolHash,
    String populationStudyPlanHash,
    String acceptanceThresholdsHash,
    String primitiveInventoryHash,
    String programGrammarHash,
    String mutationCatalogHash,
    String baselineAblationPlanHash,
    String performancePlanHash,
    String schemaBundleHash,
    FreezeStatus status,
    StageStatus trainResultStatus,
    StageStatus validationResultStatus,
    StageStatus finalTestResultStatus,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-freeze-receipt/v1";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{2,127}");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    public EvolutionRewriteProgramFreezeReceipt {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported rewrite-program freeze-receipt schema");
        }
        requireId(freezeId, "freezeId");
        requireCommit(repositoryCommit);
        requirePreExecutionState(
            dirtyStatePolicy,
            status,
            trainResultStatus,
            validationResultStatus,
            finalTestResultStatus);
        requireHashes(
            splitManifestHash,
            trainSuiteHash,
            validationCommitmentHash,
            finalTestCommitmentHash,
            evaluationProtocolHash,
            populationStudyPlanHash,
            acceptanceThresholdsHash,
            primitiveInventoryHash,
            programGrammarHash,
            mutationCatalogHash,
            baselineAblationPlanHash,
            performancePlanHash,
            schemaBundleHash);
        if (validationCommitmentHash.equals(finalTestCommitmentHash)) {
            throw new IllegalArgumentException(
                "VALIDATION and FINAL TEST commitments must differ");
        }
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            freezeId,
            repositoryCommit,
            dirtyStatePolicy,
            splitManifestHash,
            trainSuiteHash,
            validationCommitmentHash,
            finalTestCommitmentHash,
            evaluationProtocolHash,
            populationStudyPlanHash,
            acceptanceThresholdsHash,
            primitiveInventoryHash,
            programGrammarHash,
            mutationCatalogHash,
            baselineAblationPlanHash,
            performancePlanHash,
            schemaBundleHash,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "freeze-receipt contentHash mismatch");
        }
    }

    public static EvolutionRewriteProgramFreezeReceipt create(
        String freezeId,
        String repositoryCommit,
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite trainSuite,
        EvolutionRewriteProgramHeldOutCommitment validationCommitment,
        EvolutionRewriteProgramHeldOutCommitment finalTestCommitment,
        EvolutionRewriteProgramEvaluationProtocol evaluationProtocol,
        EvolutionRewriteProgramStudyPlan populationStudyPlan,
        EvolutionRewriteProgramAcceptanceThresholds acceptanceThresholds,
        String primitiveInventoryHash,
        String programGrammarHash,
        String baselineAblationPlanHash,
        EvolutionRewriteProgramPerformancePlan performancePlan,
        String schemaBundleHash
    ) {
        requireId(freezeId, "freezeId");
        requireCommit(repositoryCommit);
        validateInputs(
            splitManifest,
            trainSuite,
            validationCommitment,
            finalTestCommitment,
            evaluationProtocol,
            populationStudyPlan,
            acceptanceThresholds,
            primitiveInventoryHash,
            programGrammarHash,
            baselineAblationPlanHash,
            performancePlan,
            schemaBundleHash);
        String hash = EvolutionGenome.hash(render(
            freezeId,
            repositoryCommit,
            DirtyStatePolicy.REQUIRE_CLEAN,
            splitManifest.contentHash(),
            trainSuite.contentHash(),
            validationCommitment.contentHash(),
            finalTestCommitment.contentHash(),
            evaluationProtocol.contentHash(),
            populationStudyPlan.contentHash(),
            acceptanceThresholds.contentHash(),
            primitiveInventoryHash,
            programGrammarHash,
            populationStudyPlan.mutationCatalogHash(),
            baselineAblationPlanHash,
            performancePlan.contentHash(),
            schemaBundleHash,
            null));
        return new EvolutionRewriteProgramFreezeReceipt(
            SCHEMA,
            freezeId,
            repositoryCommit,
            DirtyStatePolicy.REQUIRE_CLEAN,
            splitManifest.contentHash(),
            trainSuite.contentHash(),
            validationCommitment.contentHash(),
            finalTestCommitment.contentHash(),
            evaluationProtocol.contentHash(),
            populationStudyPlan.contentHash(),
            acceptanceThresholds.contentHash(),
            primitiveInventoryHash,
            programGrammarHash,
            populationStudyPlan.mutationCatalogHash(),
            baselineAblationPlanHash,
            performancePlan.contentHash(),
            schemaBundleHash,
            FreezeStatus.FROZEN_NOT_RUN,
            StageStatus.NOT_EVALUATED,
            StageStatus.NOT_EVALUATED,
            StageStatus.NOT_EVALUATED,
            hash);
    }

    public void requireInputs(
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite trainSuite,
        EvolutionRewriteProgramHeldOutCommitment validationCommitment,
        EvolutionRewriteProgramHeldOutCommitment finalTestCommitment,
        EvolutionRewriteProgramEvaluationProtocol evaluationProtocol,
        EvolutionRewriteProgramStudyPlan populationStudyPlan,
        EvolutionRewriteProgramAcceptanceThresholds acceptanceThresholds,
        String primitiveInventoryHash,
        String programGrammarHash,
        String baselineAblationPlanHash,
        EvolutionRewriteProgramPerformancePlan performancePlan,
        String schemaBundleHash
    ) {
        validateInputs(
            splitManifest,
            trainSuite,
            validationCommitment,
            finalTestCommitment,
            evaluationProtocol,
            populationStudyPlan,
            acceptanceThresholds,
            primitiveInventoryHash,
            programGrammarHash,
            baselineAblationPlanHash,
            performancePlan,
            schemaBundleHash);
        if (!splitManifest.contentHash().equals(splitManifestHash)
                || !trainSuite.contentHash().equals(trainSuiteHash)
                || !validationCommitment.contentHash().equals(
                    validationCommitmentHash)
                || !finalTestCommitment.contentHash().equals(
                    finalTestCommitmentHash)
                || !evaluationProtocol.contentHash().equals(
                    evaluationProtocolHash)
                || !populationStudyPlan.contentHash().equals(
                    populationStudyPlanHash)
                || !acceptanceThresholds.contentHash().equals(
                    acceptanceThresholdsHash)
                || !primitiveInventoryHash.equals(this.primitiveInventoryHash)
                || !programGrammarHash.equals(this.programGrammarHash)
                || !populationStudyPlan.mutationCatalogHash().equals(
                    mutationCatalogHash)
                || !baselineAblationPlanHash.equals(
                    this.baselineAblationPlanHash)
                || !performancePlan.contentHash().equals(performancePlanHash)
                || !schemaBundleHash.equals(this.schemaBundleHash)) {
            throw new IllegalArgumentException(
                "freeze receipt input identity mismatch");
        }
    }

    public String toCanonicalJson() {
        return render(
            freezeId,
            repositoryCommit,
            dirtyStatePolicy,
            splitManifestHash,
            trainSuiteHash,
            validationCommitmentHash,
            finalTestCommitmentHash,
            evaluationProtocolHash,
            populationStudyPlanHash,
            acceptanceThresholdsHash,
            primitiveInventoryHash,
            programGrammarHash,
            mutationCatalogHash,
            baselineAblationPlanHash,
            performancePlanHash,
            schemaBundleHash,
            contentHash);
    }

    private static void validateInputs(
        EvolutionSplitManifest splitManifest,
        EvolutionRewriteProgramTrainSuite trainSuite,
        EvolutionRewriteProgramHeldOutCommitment validationCommitment,
        EvolutionRewriteProgramHeldOutCommitment finalTestCommitment,
        EvolutionRewriteProgramEvaluationProtocol evaluationProtocol,
        EvolutionRewriteProgramStudyPlan populationStudyPlan,
        EvolutionRewriteProgramAcceptanceThresholds acceptanceThresholds,
        String primitiveInventoryHash,
        String programGrammarHash,
        String baselineAblationPlanHash,
        EvolutionRewriteProgramPerformancePlan performancePlan,
        String schemaBundleHash
    ) {
        Objects.requireNonNull(splitManifest, "splitManifest");
        Objects.requireNonNull(trainSuite, "trainSuite");
        Objects.requireNonNull(validationCommitment, "validationCommitment");
        Objects.requireNonNull(finalTestCommitment, "finalTestCommitment");
        Objects.requireNonNull(evaluationProtocol, "evaluationProtocol");
        Objects.requireNonNull(populationStudyPlan, "populationStudyPlan");
        Objects.requireNonNull(acceptanceThresholds, "acceptanceThresholds");
        Objects.requireNonNull(performancePlan, "performancePlan");
        if (validationCommitment.split()
                != EvolutionRewriteProgramHeldOutCommitment.Split.VALIDATION
                || finalTestCommitment.split()
                    != EvolutionRewriteProgramHeldOutCommitment.Split.FINAL_TEST) {
            throw new IllegalArgumentException(
                "held-out commitments are bound to the wrong splits");
        }
        validationCommitment.requireMatches(splitManifest);
        finalTestCommitment.requireMatches(splitManifest);
        if (!populationStudyPlan.splitManifestHash().equals(
                splitManifest.contentHash())
                || !populationStudyPlan.trainSuiteHash().equals(
                    trainSuite.contentHash())
                || !populationStudyPlan.trainEvaluationProtocolHash().equals(
                    evaluationProtocol.contentHash())) {
            throw new IllegalArgumentException(
                "population plan is not bound to freeze inputs");
        }
        requireHashes(
            primitiveInventoryHash,
            programGrammarHash,
            baselineAblationPlanHash,
            performancePlan.contentHash(),
            schemaBundleHash);
    }

    private static void requirePreExecutionState(
        DirtyStatePolicy dirtyStatePolicy,
        FreezeStatus status,
        StageStatus trainResultStatus,
        StageStatus validationResultStatus,
        StageStatus finalTestResultStatus
    ) {
        if (dirtyStatePolicy != DirtyStatePolicy.REQUIRE_CLEAN
                || status != FreezeStatus.FROZEN_NOT_RUN
                || trainResultStatus != StageStatus.NOT_EVALUATED
                || validationResultStatus != StageStatus.NOT_EVALUATED
                || finalTestResultStatus != StageStatus.NOT_EVALUATED) {
            throw new IllegalArgumentException(
                "freeze receipt must remain clean and pre-execution");
        }
    }

    private static void requireHashes(String... hashes) {
        for (int index = 0; index < hashes.length; index++) {
            EvolutionGenome.requireSha256(hashes[index], "hash[" + index + "]");
        }
    }

    private static void requireId(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has invalid syntax");
        }
    }

    private static void requireCommit(String value) {
        if (value == null || !COMMIT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "repositoryCommit must be a lowercase 40-character Git commit");
        }
    }

    private static String render(
        String freezeId,
        String repositoryCommit,
        DirtyStatePolicy dirtyStatePolicy,
        String splitManifestHash,
        String trainSuiteHash,
        String validationCommitmentHash,
        String finalTestCommitmentHash,
        String evaluationProtocolHash,
        String populationStudyPlanHash,
        String acceptanceThresholdsHash,
        String primitiveInventoryHash,
        String programGrammarHash,
        String mutationCatalogHash,
        String baselineAblationPlanHash,
        String performancePlanHash,
        String schemaBundleHash,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("freezeId", freezeId)
            .property("repositoryCommit", repositoryCommit)
            .property("dirtyStatePolicy", dirtyStatePolicy.name())
            .property("splitManifestHash", splitManifestHash)
            .property("trainSuiteHash", trainSuiteHash)
            .property("validationCommitmentHash",
                validationCommitmentHash)
            .property("finalTestCommitmentHash", finalTestCommitmentHash)
            .property("evaluationProtocolHash", evaluationProtocolHash)
            .property("populationStudyPlanHash", populationStudyPlanHash)
            .property("acceptanceThresholdsHash", acceptanceThresholdsHash)
            .property("primitiveInventoryHash", primitiveInventoryHash)
            .property("programGrammarHash", programGrammarHash)
            .property("mutationCatalogHash", mutationCatalogHash)
            .property("baselineAblationPlanHash", baselineAblationPlanHash)
            .property("performancePlanHash", performancePlanHash)
            .property("schemaBundleHash", schemaBundleHash)
            .property("status", FreezeStatus.FROZEN_NOT_RUN.name())
            .property("trainResultStatus", StageStatus.NOT_EVALUATED.name())
            .property("validationResultStatus",
                StageStatus.NOT_EVALUATED.name())
            .property("finalTestResultStatus",
                StageStatus.NOT_EVALUATED.name());
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    public enum DirtyStatePolicy {
        REQUIRE_CLEAN
    }

    public enum FreezeStatus {
        FROZEN_NOT_RUN
    }

    public enum StageStatus {
        NOT_EVALUATED
    }
}
