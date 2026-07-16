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

    public ReleaseRun run() {
        return run(null);
    }

    public ReleaseRun run(Path hiddenRuleReport) {
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
        ReleaseReadinessMatrix.MatrixReport matrix =
            ReleaseReadinessMatrix.evaluate(evidence, hiddenRuleEvidence);
        String profileCatalog = ReleaseEvidenceProfile.catalogJson();
        String profileCatalogHash = AutonomousResearchBriefV2.hash(profileCatalog);
        String contentHash = runHash(
            profileCatalogHash,
            evidence.evidenceHash(),
            matrix.hiddenRuleEvidenceHash(),
            matrix.contentHash(),
            campaigns.getFirst().contentHash());
        return new ReleaseRun(
            SCHEMA,
            campaigns.getFirst(),
            evidence,
            hiddenRuleEvidence,
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
            if (run.hiddenRuleEvidence() != null) {
                write(outputDirectory.resolve("hidden-rule-release-evidence.json"),
                    run.hiddenRuleEvidence().toCanonicalJson());
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

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String runHash(
        String profileCatalogHash,
        String evidenceHash,
        String hiddenRuleEvidenceHash,
        String matrixHash,
        String campaignHash
    ) {
        return AutonomousResearchBriefV2.hash(
            SCHEMA
                + "\nprofileCatalog=" + profileCatalogHash
                + "\nevidence=" + evidenceHash
                + "\nhiddenRuleEvidence=" + hiddenRuleEvidenceHash
                + "\nmatrix=" + matrixHash
                + "\ncampaign=" + campaignHash);
    }

    public record ReleaseRun(
        String schema,
        CampaignRun retainedCampaign,
        AutonomousCampaignReleaseEvidence evidence,
        HiddenRuleBenchmarkReleaseEvidence hiddenRuleEvidence,
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
            if (!retainedCampaign.contentHash()
                    .equals(evidence.campaignManifestHash())
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
