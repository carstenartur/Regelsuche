package de.regelsuche.evolution;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Temporal manifest around actual combined qualification evidence; contains no approval/PASS field. */
public record LearnedSelectedProgramAuthorizationBundle(String schema, String finalPlanHash,
    String selectedConfigurationHash, String finalTestEvaluationHash, String qualificationAssessmentHash,
    String splitManifestHash, String repositoryRevision, Instant issuedAt, Instant expiresAt, String contentHash) {
    public static final String SCHEMA = "regelsuche.learned-selected-program-authorization-bundle/v1";

    public LearnedSelectedProgramAuthorizationBundle {
        if (!SCHEMA.equals(schema)) { throw new IllegalArgumentException("unsupported selected-program authorization bundle"); }
        for (String value : List.of(finalPlanHash, selectedConfigurationHash, finalTestEvaluationHash,
                qualificationAssessmentHash, splitManifestHash)) {
            EvolutionGenome.requireSha256(value, "selected evidence hash");
        }
        LearnedPatternAuthorizationJson.requireRevision(repositoryRevision, "repositoryRevision");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!issuedAt.isBefore(expiresAt)) { throw new IllegalArgumentException("authorization interval must be nonempty"); }
        EvolutionProgramValidationJson.requireHash(contentHash, material(finalPlanHash, selectedConfigurationHash,
            finalTestEvaluationHash, qualificationAssessmentHash, splitManifestHash, repositoryRevision, issuedAt, expiresAt));
    }

    public static LearnedSelectedProgramAuthorizationBundle create(EvolutionRewriteProgramQualificationAssessment assessment,
        EvolutionSplitManifest split, Instant issuedAt, Instant expiresAt) {
        var finalTest = Objects.requireNonNull(assessment, "assessment").finalTest();
        var plan = finalTest.plan();
        if (!Objects.requireNonNull(split, "split").contentHash()
                .equals(plan.validationHandoff().selection().plan().splitManifestHash())) {
            throw new IllegalArgumentException("authorization split differs from selected study");
        }
        return new LearnedSelectedProgramAuthorizationBundle(SCHEMA, plan.contentHash(),
            plan.selectedConfiguration().contentHash(), finalTest.contentHash(), assessment.contentHash(), split.contentHash(),
            assessment.repositoryRevision(), issuedAt, expiresAt, EvolutionProgramValidationJson.hash(material(
                plan.contentHash(), plan.selectedConfiguration().contentHash(), finalTest.contentHash(), assessment.contentHash(),
                split.contentHash(), assessment.repositoryRevision(), issuedAt, expiresAt)));
    }

    void requireEvidence(EvolutionRewriteProgramFinalTestPlan plan, EvolutionSplitManifest split,
        EvolutionRewriteProgramQualificationAssessment assessment, String revision, Instant asOf) {
        requireUsableAt(plan, revision, asOf);
        if (!splitManifestHash.equals(split.contentHash()) || !assessment.finalTest().plan().equals(plan)
                || !qualificationAssessmentHash.equals(assessment.contentHash())
                || !finalTestEvaluationHash.equals(assessment.finalTest().contentHash())) {
            throw new IllegalArgumentException("authorization bundle differs from actual combined qualification evidence");
        }
    }

    void requireUsableAt(EvolutionRewriteProgramFinalTestPlan plan, String revision, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (asOf.isBefore(issuedAt) || !asOf.isBefore(expiresAt)
                || !repositoryRevision.equals(revision) || !finalPlanHash.equals(plan.contentHash())
                || !selectedConfigurationHash.equals(plan.selectedConfiguration().contentHash())
                || !splitManifestHash.equals(plan.validationHandoff().selection().plan().splitManifestHash())) {
            throw new IllegalArgumentException("selected-program authority time, revision or complete selection differs");
        }
    }

    public String toCanonicalJson() { return EvolutionProgramValidationJson.write(this); }
    public static LearnedSelectedProgramAuthorizationBundle fromCanonicalJson(String json) {
        return EvolutionProgramValidationJson.read(json, LearnedSelectedProgramAuthorizationBundle.class);
    }

    private static Map<String, Object> material(String plan, String configuration, String finalTest, String assessment,
        String split, String revision, Instant issuedAt, Instant expiresAt) {
        return EvolutionProgramValidationJson.material(SCHEMA, "finalPlanHash", plan, "selectedConfigurationHash", configuration,
            "finalTestEvaluationHash", finalTest, "qualificationAssessmentHash", assessment, "splitManifestHash", split,
            "repositoryRevision", revision, "issuedAt", issuedAt, "expiresAt", expiresAt);
    }
}
