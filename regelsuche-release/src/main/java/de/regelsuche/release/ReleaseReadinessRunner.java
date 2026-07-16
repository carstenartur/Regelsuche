package de.regelsuche.release;

import de.regelsuche.experiments.autopilot.AutonomousProductionCampaignRunner;
import de.regelsuche.experiments.autopilot.AutonomousProductionCampaignRunner.CampaignRun;
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
        AutonomousProductionCampaignRunner campaignRunner =
            new AutonomousProductionCampaignRunner();
        List<CampaignRun> campaigns = List.of(
            campaignRunner.runPinned(1),
            campaignRunner.runPinned(2),
            campaignRunner.runPinned(4));
        AutonomousCampaignReleaseEvidence evidence =
            AutonomousCampaignReleaseEvidence.from(campaigns);
        ReleaseReadinessMatrix.MatrixReport matrix =
            ReleaseReadinessMatrix.evaluate(evidence);
        String profileCatalog = ReleaseEvidenceProfile.catalogJson();
        String profileCatalogHash = de.regelsuche.experiments.autopilot
            .AutonomousResearchBriefV2.hash(profileCatalog);
        String contentHash = de.regelsuche.experiments.autopilot
            .AutonomousResearchBriefV2.hash(
                SCHEMA
                    + "\nprofileCatalog=" + profileCatalogHash
                    + "\nevidence=" + evidence.evidenceHash()
                    + "\nmatrix=" + matrix.contentHash()
                    + "\ncampaign=" + campaigns.getFirst().contentHash());
        return new ReleaseRun(
            SCHEMA,
            campaigns.getFirst(),
            evidence,
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

    public record ReleaseRun(
        String schema,
        CampaignRun retainedCampaign,
        AutonomousCampaignReleaseEvidence evidence,
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
            if (!retainedCampaign.contentHash()
                    .equals(evidence.campaignManifestHash())
                    || !evidence.evidenceHash().equals(matrix.evidenceHash())) {
                throw new IllegalArgumentException(
                    "release-readiness run artifacts are not hash-linked");
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
