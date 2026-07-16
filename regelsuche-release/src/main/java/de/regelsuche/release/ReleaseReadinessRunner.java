package de.regelsuche.release;

import de.regelsuche.experiments.autopilot.AutonomousProductionCampaignRunner;
import de.regelsuche.experiments.autopilot.AutonomousProductionCampaignRunner.CampaignRun;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2;
import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Executes real campaign evidence and evaluates every release profile. */
public final class ReleaseReadinessRunner {
    public static final String SCHEMA = "regelsuche.release-readiness-run/v1";
    public static final String NO_QUALIFICATION_EVIDENCE_HASH =
        AutonomousResearchBriefV2.hash(
            "regelsuche.autonomous-candidate-qualification/NOT_PROVIDED");

    public ReleaseRun run() {
        return run(null, false);
    }

    public ReleaseRun run(Path hiddenRuleReport) {
        return run(hiddenRuleReport, false);
    }

    public ReleaseRun runQualified(Path hiddenRuleReport) {
        return run(hiddenRuleReport, true);
    }

    private ReleaseRun run(Path hiddenRuleReport, boolean qualifyCandidate) {
        AutonomousProductionCampaignRunner campaignRunner =
            new AutonomousProductionCampaignRunner();
        List<CampaignRun> campaigns = List.of(
            campaignRunner.runPinned(1),
            campaignRunner.runPinned(2),
            campaignRunner.runPinned(4));
        AutonomousCampaignReleaseEvidence evidence =
            AutonomousCampaignReleaseEvidence.from(campaigns);
        HiddenRuleBenchmarkReleaseEvidence hiddenRuleEvidence =
            hiddenRuleReport == null
                ? null
                : HiddenRuleBenchmarkReleaseEvidence.read(hiddenRuleReport);
        ReleaseReadinessMatrix.MatrixReport baseMatrix =
            ReleaseReadinessMatrix.evaluate(evidence, hiddenRuleEvidence);

        AutonomousCandidateQualificationRunner.QualificationRun qualificationRun = null;
        AutonomousCandidateQualificationEvidence qualificationEvidence = null;
        if (qualifyCandidate) {
            AutonomousCandidateQualificationRunner qualificationRunner =
                new AutonomousCandidateQualificationRunner();
            List<AutonomousCandidateQualificationRunner.QualificationRun> runs =
                campaigns.stream().map(qualificationRunner::run).toList();
            if (runs.stream().map(run -> run.evidence().contentHash())
                    .distinct().count() != 1L
                    || runs.stream().map(
                        AutonomousCandidateQualificationRunner.QualificationRun::contentHash)
                        .distinct().count() != 1L) {
                throw new IllegalStateException(
                    "candidate qualification is not reproducible across clean campaign runs");
            }
            qualificationRun = runs.getFirst();
            qualificationEvidence = qualificationRun.evidence();
        }
        ReleaseReadinessMatrix.MatrixReport matrix =
            new ReleaseQualificationMatrixAdapter().apply(
                baseMatrix, evidence, qualificationEvidence);
        String profileCatalog = ReleaseEvidenceProfile.catalogJson();
        String profileCatalogHash = AutonomousResearchBriefV2.hash(profileCatalog);
        String qualificationHash = qualificationEvidence == null
            ? NO_QUALIFICATION_EVIDENCE_HASH
            : qualificationEvidence.contentHash();
        String contentHash = runHash(
            profileCatalogHash,
            evidence.evidenceHash(),
            matrix.hiddenRuleEvidenceHash(),
            qualificationHash,
            matrix.contentHash(),
            campaigns.getFirst().contentHash());
        return new ReleaseRun(
            SCHEMA,
            campaigns.getFirst(),
            evidence,
            hiddenRuleEvidence,
            qualificationRun,
            qualificationEvidence,
            matrix,
            profileCatalog,
            profileCatalogHash,
            contentHash);
    }

    public void write(Path outputDirectory, ReleaseRun run) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(run, "run");
        try {
            Files.createDirectories(outputDirectory);
            new AutonomousProductionCampaignRunner().write(
                outputDirectory.resolve("campaign"),
                run.retainedCampaign());
            write(outputDirectory.resolve("profiles.json"), run.profileCatalogJson());
            write(outputDirectory.resolve("evidence-summary.json"),
                run.evidence().toCanonicalJson());
            Path hiddenOutput = outputDirectory.resolve(
                "hidden-rule-release-evidence.json");
            if (run.hiddenRuleEvidence() == null) {
                Files.deleteIfExists(hiddenOutput);
            } else {
                write(hiddenOutput, run.hiddenRuleEvidence().toCanonicalJson());
            }
            Path qualificationDirectory = outputDirectory.resolve("qualification");
            if (run.qualificationRun() == null) {
                deleteQualificationOutputs(qualificationDirectory);
            } else {
                new AutonomousCandidateQualificationRunner().write(
                    qualificationDirectory,
                    run.qualificationRun());
            }
            write(outputDirectory.resolve("release-readiness-report.json"),
                run.matrix().toCanonicalJson());
            write(outputDirectory.resolve("release-readiness-run.json"),
                run.toCanonicalJson());
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write release-readiness evidence", exception);
        }
    }

    private static void deleteQualificationOutputs(Path directory) throws IOException {
        for (String file : List.of(
                "qualification-suite.json",
                "qualification-split-audit.json",
                "qualification-evaluation.json",
                "qualification-utility.json",
                "candidate-qualification-evidence.json",
                "candidate-qualification-run.json")) {
            Files.deleteIfExists(directory.resolve(file));
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String runHash(
        String profileCatalogHash,
        String evidenceHash,
        String hiddenRuleEvidenceHash,
        String qualificationEvidenceHash,
        String matrixHash,
        String campaignHash
    ) {
        return AutonomousResearchBriefV2.hash(
            SCHEMA
                + "\nprofileCatalog=" + profileCatalogHash
                + "\nevidence=" + evidenceHash
                + "\nhiddenRuleEvidence=" + hiddenRuleEvidenceHash
                + "\nqualificationEvidence=" + qualificationEvidenceHash
                + "\nmatrix=" + matrixHash
                + "\ncampaign=" + campaignHash);
    }

    public record ReleaseRun(
        String schema,
        CampaignRun retainedCampaign,
        AutonomousCampaignReleaseEvidence evidence,
        HiddenRuleBenchmarkReleaseEvidence hiddenRuleEvidence,
        AutonomousCandidateQualificationRunner.QualificationRun qualificationRun,
        AutonomousCandidateQualificationEvidence qualificationEvidence,
        ReleaseReadinessMatrix.MatrixReport matrix,
        String profileCatalogJson,
        String profileCatalogHash,
        String contentHash
    ) {
        public ReleaseRun {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported release-readiness run schema");
            }
            retainedCampaign = Objects.requireNonNull(
                retainedCampaign, "retainedCampaign");
            evidence = Objects.requireNonNull(evidence, "evidence");
            matrix = Objects.requireNonNull(matrix, "matrix");
            if (profileCatalogJson == null || profileCatalogJson.isBlank()) {
                throw new IllegalArgumentException(
                    "profileCatalogJson must not be blank");
            }
            requireSha256(profileCatalogHash, "profileCatalogHash");
            requireSha256(contentHash, "contentHash");
            String expectedHiddenHash = hiddenRuleEvidence == null
                ? ReleaseReadinessMatrix.NO_HIDDEN_RULE_EVIDENCE_HASH
                : hiddenRuleEvidence.evidenceHash();
            String expectedQualificationHash = qualificationEvidence == null
                ? NO_QUALIFICATION_EVIDENCE_HASH
                : qualificationEvidence.contentHash();
            if ((qualificationRun == null) != (qualificationEvidence == null)
                    || qualificationRun != null
                        && !qualificationRun.evidence().contentHash()
                            .equals(qualificationEvidence.contentHash())
                    || !retainedCampaign.contentHash()
                        .equals(evidence.campaignManifestHash())
                    || qualificationEvidence != null
                        && !retainedCampaign.contentHash().equals(
                            qualificationEvidence.campaignManifestHash())
                    || !evidence.evidenceHash().equals(matrix.evidenceHash())
                    || !expectedHiddenHash.equals(matrix.hiddenRuleEvidenceHash())
                    || !AutonomousResearchBriefV2.hash(profileCatalogJson)
                        .equals(profileCatalogHash)) {
                throw new IllegalArgumentException(
                    "release-readiness run artifacts are not hash-linked");
            }
            String expectedHash = runHash(
                profileCatalogHash,
                evidence.evidenceHash(),
                expectedHiddenHash,
                expectedQualificationHash,
                matrix.contentHash(),
                retainedCampaign.contentHash());
            if (!expectedHash.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "release-readiness run hash does not match canonical fields");
            }
        }

        public boolean autonomousCampaignReady() {
            return matrix.autonomyClaimAuthorized();
        }

        public String toCanonicalJson() {
            String qualificationHash = qualificationEvidence == null
                ? NO_QUALIFICATION_EVIDENCE_HASH
                : qualificationEvidence.contentHash();
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("campaignManifestHash",
                    retainedCampaign.contentHash())
                .property("profileCatalogHash", profileCatalogHash)
                .property("evidenceHash", evidence.evidenceHash())
                .property("hiddenRuleEvidenceStatus",
                    hiddenRuleEvidence == null ? "NOT_PROVIDED" : "BOUND")
                .property("hiddenRuleEvidenceHash",
                    matrix.hiddenRuleEvidenceHash())
                .property("qualificationEvidenceStatus",
                    qualificationEvidence == null ? "NOT_PROVIDED" : "BOUND")
                .property("qualificationEvidenceHash", qualificationHash)
                .property("matrixHash", matrix.contentHash())
                .property("autonomousCampaignStatus",
                    matrix.autonomousCampaignStatus().name())
                .property("autonomyClaimAuthorized",
                    matrix.autonomyClaimAuthorized())
                .property("promotionStatus", "NOT_EVALUATED")
                .property("publicEvidenceStatus", "NOT_EVALUATED")
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }
}
