package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionPopulationEngine.PopulationRun;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.CaseCommitment;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.json.JsonWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Content-addressed evidence authorizing one held-out reveal stage.
 *
 * <p>The authorization is created only from already validated runtime
 * artifacts. It cross-binds the study, split manifest, public held-out
 * commitment and the exact prerequisite evidence. Private reveal custody
 * remains an additional external access boundary.</p>
 */
public final class EvolutionRewriteProgramHeldOutRevealAuthorization {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-held-out-reveal-authorization/v1";

    private final String studyId;
    private final Split split;
    private final Stage stage;
    private final String studyPlanHash;
    private final String splitManifestHash;
    private final String heldOutCommitmentHash;
    private final String prerequisiteArtifactHash;
    private final String validationSelectionHash;
    private final String heldOutSuiteHash;
    private final String terminalOutcome;
    private final String contentHash;

    private EvolutionRewriteProgramHeldOutRevealAuthorization(
        String studyId,
        Split split,
        Stage stage,
        String studyPlanHash,
        String splitManifestHash,
        String heldOutCommitmentHash,
        String prerequisiteArtifactHash,
        String validationSelectionHash,
        String heldOutSuiteHash,
        String terminalOutcome,
        String contentHash
    ) {
        EvolutionValidationArtifactSupport.requireText(studyId, "studyId");
        this.studyId = studyId;
        this.split = Objects.requireNonNull(split, "split");
        this.stage = Objects.requireNonNull(stage, "stage");
        EvolutionGenome.requireSha256(studyPlanHash, "studyPlanHash");
        this.studyPlanHash = studyPlanHash;
        EvolutionGenome.requireSha256(splitManifestHash, "splitManifestHash");
        this.splitManifestHash = splitManifestHash;
        EvolutionGenome.requireSha256(
            heldOutCommitmentHash, "heldOutCommitmentHash");
        this.heldOutCommitmentHash = heldOutCommitmentHash;
        EvolutionGenome.requireSha256(
            prerequisiteArtifactHash, "prerequisiteArtifactHash");
        this.prerequisiteArtifactHash = prerequisiteArtifactHash;
        this.validationSelectionHash = normalizeOptionalHash(
            validationSelectionHash, "validationSelectionHash");
        this.heldOutSuiteHash = normalizeOptionalHash(
            heldOutSuiteHash, "heldOutSuiteHash");
        this.terminalOutcome = terminalOutcome == null ? "" : terminalOutcome;
        requireStageShape();
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            studyId,
            split,
            stage,
            studyPlanHash,
            splitManifestHash,
            heldOutCommitmentHash,
            prerequisiteArtifactHash,
            this.validationSelectionHash,
            this.heldOutSuiteHash,
            this.terminalOutcome,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "held-out reveal authorization contentHash mismatch");
        }
        this.contentHash = contentHash;
    }

    public static EvolutionRewriteProgramHeldOutRevealAuthorization validation(
        EvolutionStudyPlan plan,
        EvolutionSplitManifest manifest,
        PopulationRun populationRun,
        EvolutionRewriteProgramHeldOutCommitment commitment
    ) {
        requireStudy(plan, manifest);
        Objects.requireNonNull(populationRun, "populationRun");
        requireCommitment(commitment, manifest, Split.VALIDATION);
        if (!populationRun.studyPlanHash().equals(plan.contentHash())) {
            throw new IllegalArgumentException(
                "TRAIN population run differs from the frozen study plan");
        }
        String terminal = populationRun.terminalOutcome().name();
        return create(
            plan.studyId(),
            Split.VALIDATION,
            Stage.VALIDATION_AFTER_TRAIN_POPULATION_COMPLETE,
            plan.contentHash(),
            manifest.contentHash(),
            commitment.contentHash(),
            populationRun.contentHash(),
            "",
            "",
            terminal);
    }

    public static EvolutionRewriteProgramHeldOutRevealAuthorization finalTest(
        EvolutionStudyPlan plan,
        EvolutionSplitManifest manifest,
        EvolutionValidationSelection selection,
        EvolutionFinalTestSuite suite,
        EvolutionFinalTestReservation reservation,
        EvolutionRewriteProgramHeldOutCommitment commitment
    ) {
        requireStudy(plan, manifest);
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(suite, "suite");
        Objects.requireNonNull(reservation, "reservation");
        requireCommitment(commitment, manifest, Split.FINAL_TEST);
        if (!selection.hasSelection()) {
            throw new IllegalArgumentException(
                "FINAL TEST reveal requires a frozen selected configuration");
        }
        if (!selection.studyPlanHash().equals(plan.contentHash())
                || !selection.splitManifestHash().equals(manifest.contentHash())) {
            throw new IllegalArgumentException(
                "VALIDATION selection differs from the frozen study or split");
        }
        if (!suite.studyPlanHash().equals(plan.contentHash())
                || !suite.splitManifestHash().equals(manifest.contentHash())) {
            throw new IllegalArgumentException(
                "FINAL TEST suite differs from the frozen study or split");
        }
        if (!reservation.studyPlanHash().equals(plan.contentHash())
                || !reservation.splitManifestHash().equals(manifest.contentHash())
                || !reservation.validationSelectionHash().equals(
                    selection.contentHash())
                || !reservation.finalTestSuiteHash().equals(suite.contentHash())) {
            throw new IllegalArgumentException(
                "FINAL TEST reservation differs from selection or suite");
        }
        requireSuiteMatchesCommitment(suite, commitment);
        return create(
            plan.studyId(),
            Split.FINAL_TEST,
            Stage.FINAL_TEST_AFTER_FROZEN_SELECTION_AND_RESERVATION,
            plan.contentHash(),
            manifest.contentHash(),
            commitment.contentHash(),
            reservation.contentHash(),
            selection.contentHash(),
            suite.contentHash(),
            EvolutionFinalTestReservation.RESERVED);
    }

    private static EvolutionRewriteProgramHeldOutRevealAuthorization create(
        String studyId,
        Split split,
        Stage stage,
        String studyPlanHash,
        String splitManifestHash,
        String heldOutCommitmentHash,
        String prerequisiteArtifactHash,
        String validationSelectionHash,
        String heldOutSuiteHash,
        String terminalOutcome
    ) {
        String hash = EvolutionGenome.hash(render(
            studyId,
            split,
            stage,
            studyPlanHash,
            splitManifestHash,
            heldOutCommitmentHash,
            prerequisiteArtifactHash,
            validationSelectionHash,
            heldOutSuiteHash,
            terminalOutcome,
            null));
        return new EvolutionRewriteProgramHeldOutRevealAuthorization(
            studyId,
            split,
            stage,
            studyPlanHash,
            splitManifestHash,
            heldOutCommitmentHash,
            prerequisiteArtifactHash,
            validationSelectionHash,
            heldOutSuiteHash,
            terminalOutcome,
            hash);
    }

    void requireMatches(
        EvolutionRewriteProgramHeldOutRevealBundle bundle,
        EvolutionRewriteProgramHeldOutCommitment commitment
    ) {
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(commitment, "commitment");
        if (!studyId.equals(bundle.studyId())
                || split != bundle.split()
                || !heldOutCommitmentHash.equals(commitment.contentHash())
                || !bundle.commitment().equals(commitment)
                || !bundle.contentHash().equals(commitment.sealedRevealHash())) {
            throw new IllegalArgumentException(
                "held-out reveal authorization differs from bundle or commitment");
        }
    }

    public String studyId() {
        return studyId;
    }

    public Split split() {
        return split;
    }

    public Stage stage() {
        return stage;
    }

    public String studyPlanHash() {
        return studyPlanHash;
    }

    public String splitManifestHash() {
        return splitManifestHash;
    }

    public String heldOutCommitmentHash() {
        return heldOutCommitmentHash;
    }

    public String prerequisiteArtifactHash() {
        return prerequisiteArtifactHash;
    }

    public String validationSelectionHash() {
        return validationSelectionHash;
    }

    public String heldOutSuiteHash() {
        return heldOutSuiteHash;
    }

    public String terminalOutcome() {
        return terminalOutcome;
    }

    public String contentHash() {
        return contentHash;
    }

    public String toCanonicalJson() {
        return render(
            studyId,
            split,
            stage,
            studyPlanHash,
            splitManifestHash,
            heldOutCommitmentHash,
            prerequisiteArtifactHash,
            validationSelectionHash,
            heldOutSuiteHash,
            terminalOutcome,
            contentHash);
    }

    private void requireStageShape() {
        if (stage.requiredSplit() != split) {
            throw new IllegalArgumentException(
                "held-out reveal stage differs from split");
        }
        switch (stage) {
            case VALIDATION_AFTER_TRAIN_POPULATION_COMPLETE -> {
                if (!validationSelectionHash.isEmpty()
                        || !heldOutSuiteHash.isEmpty()
                        || terminalOutcome.isEmpty()) {
                    throw new IllegalArgumentException(
                        "VALIDATION reveal authorization has invalid evidence shape");
                }
            }
            case FINAL_TEST_AFTER_FROZEN_SELECTION_AND_RESERVATION -> {
                if (validationSelectionHash.isEmpty()
                        || heldOutSuiteHash.isEmpty()
                        || !EvolutionFinalTestReservation.RESERVED.equals(
                            terminalOutcome)) {
                    throw new IllegalArgumentException(
                        "FINAL TEST reveal authorization has invalid evidence shape");
                }
            }
        }
    }

    private static void requireStudy(
        EvolutionStudyPlan plan,
        EvolutionSplitManifest manifest
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(manifest, "manifest");
        if (!plan.studyId().equals(manifest.studyId())
                || !plan.splitManifestHash().equals(manifest.contentHash())) {
            throw new IllegalArgumentException(
                "study plan differs from split manifest");
        }
    }

    private static void requireCommitment(
        EvolutionRewriteProgramHeldOutCommitment commitment,
        EvolutionSplitManifest manifest,
        Split expectedSplit
    ) {
        Objects.requireNonNull(commitment, "commitment");
        if (commitment.split() != expectedSplit) {
            throw new IllegalArgumentException(
                "held-out commitment has the wrong split");
        }
        commitment.requireMatches(manifest);
    }

    private static void requireSuiteMatchesCommitment(
        EvolutionFinalTestSuite suite,
        EvolutionRewriteProgramHeldOutCommitment commitment
    ) {
        Map<String, CaseCommitment> committedById = new HashMap<>();
        commitment.cases().forEach(item ->
            committedById.put(item.caseId(), item));
        if (suite.cases().size() != committedById.size()) {
            throw new IllegalArgumentException(
                "FINAL TEST suite differs from held-out commitment size");
        }
        for (EvolutionFinalTestSuite.CaseDefinition item : suite.cases()) {
            CaseCommitment committed = committedById.get(item.caseId());
            if (committed == null
                    || !item.caseMaterialHash().equals(
                        committed.revealEntryHash())) {
                throw new IllegalArgumentException(
                    "FINAL TEST suite case differs from held-out commitment: "
                        + item.caseId());
            }
        }
    }

    private static String normalizeOptionalHash(String value, String field) {
        String normalized = value == null ? "" : value;
        if (!normalized.isEmpty()) {
            EvolutionGenome.requireSha256(normalized, field);
        }
        return normalized;
    }

    private static String render(
        String studyId,
        Split split,
        Stage stage,
        String studyPlanHash,
        String splitManifestHash,
        String heldOutCommitmentHash,
        String prerequisiteArtifactHash,
        String validationSelectionHash,
        String heldOutSuiteHash,
        String terminalOutcome,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("studyId", studyId)
            .property("split", split.name())
            .property("stage", stage.name())
            .property("studyPlanHash", studyPlanHash)
            .property("splitManifestHash", splitManifestHash)
            .property("heldOutCommitmentHash", heldOutCommitmentHash)
            .property("prerequisiteArtifactHash", prerequisiteArtifactHash)
            .property("validationSelectionHash", validationSelectionHash)
            .property("heldOutSuiteHash", heldOutSuiteHash)
            .property("terminalOutcome", terminalOutcome);
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    public enum Stage {
        VALIDATION_AFTER_TRAIN_POPULATION_COMPLETE(Split.VALIDATION),
        FINAL_TEST_AFTER_FROZEN_SELECTION_AND_RESERVATION(Split.FINAL_TEST);

        private final Split requiredSplit;

        Stage(Split requiredSplit) {
            this.requiredSplit = requiredSplit;
        }

        Split requiredSplit() {
            return requiredSplit;
        }
    }
}
