package de.regelsuche.release;

import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence;
import de.regelsuche.discovery.domain.DomainDiscoveryExport;
import de.regelsuche.discovery.domain.DomainDiscoveryExportVerifier;
import de.regelsuche.discovery.domain.DomainDiscoveryExportVerifier.VerifiedDomainExport;
import de.regelsuche.discovery.domain.DomainDiscoveryRunner;
import de.regelsuche.discovery.domain.ExpressionRewriteDiscoveryDomain;
import de.regelsuche.discovery.domain.FiniteDifferenceSequenceDomain;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2;
import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Executes three clean runs of the reference multi-domain qualification. */
public final class DomainGenericQualificationRunner {
    public static final String SCHEMA =
        "regelsuche.domain-generic-discovery-qualification-run/v1";
    public static final int CLEAN_RUN_COUNT = 3;

    private final DomainDiscoveryExport exporter = new DomainDiscoveryExport();
    private final DomainDiscoveryExportVerifier verifier =
        new DomainDiscoveryExportVerifier();
    private final DomainGenericDiscoveryQualification qualification =
        new DomainGenericDiscoveryQualification();

    public QualificationRun run(Path outputDirectory) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Path root = outputDirectory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            List<String> runFingerprints = new ArrayList<>();
            List<VerifiedDomainExport> retained = List.of();
            for (int run = 1; run <= CLEAN_RUN_COUNT; run++) {
                Path runDirectory = root.resolve("runs/run-" + run);
                List<VerifiedDomainExport> exports = executeRun(runDirectory);
                String fingerprint = runFingerprint(exports);
                runFingerprints.add(fingerprint);
                if (run == 1) {
                    retained = exports;
                }
            }
            boolean identical = runFingerprints.stream().distinct().count() == 1L;
            DomainGenericDiscoveryQualification.QualificationReport report =
                qualification.evaluate(retained, runFingerprints.size(), identical);
            String profileCatalog = DomainGenericEvidenceProfile.catalogJson();
            String profileCatalogHash = AutonomousResearchBriefV2.hash(profileCatalog);
            QualificationRun result = QualificationRun.create(
                report,
                profileCatalog,
                profileCatalogHash,
                runFingerprints);
            write(root.resolve("profile-catalog.json"), profileCatalog);
            write(root.resolve("qualification-report.json"),
                report.toCanonicalJson());
            write(root.resolve("qualification-run.json"),
                result.toCanonicalJson());
            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not execute domain-generic qualification", exception);
        }
    }

    private List<VerifiedDomainExport> executeRun(Path directory)
            throws IOException {
        DomainDiscoveryEvidence expression = expressionEvidence();
        DomainDiscoveryEvidence sequence = sequenceEvidence();
        List<DomainInput> inputs = List.of(
            new DomainInput("expression", expression),
            new DomainInput("sequence", sequence));
        List<VerifiedDomainExport> verified = new ArrayList<>();
        Path receipts = directory.resolve("verification-receipts");
        Files.createDirectories(receipts);
        for (DomainInput input : inputs) {
            Path exportDirectory = directory.resolve(input.name());
            exporter.write(exportDirectory, input.evidence());
            VerifiedDomainExport snapshot = verifier.requireVerified(exportDirectory);
            verified.add(snapshot);
            write(
                receipts.resolve(input.name() + ".json"),
                snapshot.verification().toCanonicalJson());
        }
        return verified.stream()
            .sorted(Comparator.comparing(item -> item.manifest().domainId()))
            .toList();
    }

    private static String runFingerprint(List<VerifiedDomainExport> exports) {
        List<String> material = exports.stream()
            .sorted(Comparator.comparing(item -> item.manifest().domainId()))
            .map(item -> item.manifest().domainId() + '|'
                + item.manifest().contentHash() + '|'
                + item.verification().contentHash())
            .toList();
        return AutonomousResearchBriefV2.hash(
            "regelsuche.domain-generic-clean-run/v1\nexports=" + material);
    }

    private static DomainDiscoveryEvidence expressionEvidence() {
        var domain = new ExpressionRewriteDiscoveryDomain("x");
        return new DomainDiscoveryRunner().run(
            "domain-generic-expression-qualification",
            domain,
            DiscoverySeed.create(
                "domain-generic-expression-seed",
                domain.domainId(),
                "x + 0",
                "domain-generic-qualification/v1"),
            new DiscoveryBudget(2, 20, 100, 80, 5, 20)).evidence();
    }

    private static DomainDiscoveryEvidence sequenceEvidence() {
        var domain = new FiniteDifferenceSequenceDomain();
        return new DomainDiscoveryRunner().run(
            "domain-generic-sequence-qualification",
            domain,
            DiscoverySeed.create(
                "domain-generic-sequence-seed",
                domain.domainId(),
                "observed=1,4,9,16;holdout=25,36",
                "domain-generic-qualification/v1"),
            new DiscoveryBudget(4, 20, 20, 10, 5, 20)).evidence();
    }

    static DomainDiscoveryEvidence refutedSequenceEvidence() {
        var domain = new FiniteDifferenceSequenceDomain();
        return new DomainDiscoveryRunner().run(
            "domain-generic-refuted-sequence",
            domain,
            DiscoverySeed.create(
                "domain-generic-refuted-sequence-seed",
                domain.domainId(),
                "observed=1,4,9,16;holdout=26",
                "domain-generic-qualification/v1"),
            new DiscoveryBudget(4, 20, 20, 10, 5, 20)).evidence();
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private record DomainInput(String name, DomainDiscoveryEvidence evidence) {
        private DomainInput {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    public record QualificationRun(
        String schema,
        String profileCatalogHash,
        String qualificationReportHash,
        List<String> cleanRunFingerprints,
        boolean cleanRunsIdentical,
        String domainGenericQualificationStatus,
        boolean domainGenericClaimAuthorized,
        boolean autonomousCampaignClaimAuthorized,
        String proofStatus,
        String externalNoveltyStatus,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash,
        String profileCatalogJson,
        DomainGenericDiscoveryQualification.QualificationReport report
    ) {
        public QualificationRun {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported domain-generic qualification run schema");
            }
            requireSha256(profileCatalogHash, "profileCatalogHash");
            requireSha256(qualificationReportHash, "qualificationReportHash");
            cleanRunFingerprints = cleanRunFingerprints == null
                ? List.of()
                : cleanRunFingerprints.stream()
                    .map(value -> requireSha256(value, "cleanRunFingerprint"))
                    .toList();
            if (profileCatalogJson == null || profileCatalogJson.isBlank()) {
                throw new IllegalArgumentException(
                    "profileCatalogJson must not be blank");
            }
            Objects.requireNonNull(report, "report");
            if (!AutonomousResearchBriefV2.hash(profileCatalogJson)
                    .equals(profileCatalogHash)
                    || !report.contentHash().equals(qualificationReportHash)
                    || cleanRunsIdentical
                        != (cleanRunFingerprints.size() >= CLEAN_RUN_COUNT
                            && cleanRunFingerprints.stream().distinct().count() == 1L)
                    || !report.status().name().equals(
                        domainGenericQualificationStatus)
                    || report.domainGenericClaimAuthorized()
                        != domainGenericClaimAuthorized
                    || report.autonomousCampaignClaimAuthorized()
                        != autonomousCampaignClaimAuthorized
                    || !report.proofStatus().equals(proofStatus)
                    || !report.externalNoveltyStatus().equals(externalNoveltyStatus)
                    || !report.promotionStatus().equals(promotionStatus)
                    || !report.publicEvidenceStatus().equals(publicEvidenceStatus)) {
                throw new IllegalArgumentException(
                    "domain-generic qualification run artifacts are not linked");
            }
            requireSha256(contentHash, "contentHash");
            String expected = hash(
                profileCatalogHash,
                qualificationReportHash,
                cleanRunFingerprints,
                cleanRunsIdentical,
                domainGenericQualificationStatus,
                domainGenericClaimAuthorized,
                autonomousCampaignClaimAuthorized,
                proofStatus,
                externalNoveltyStatus,
                promotionStatus,
                publicEvidenceStatus);
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "domain-generic qualification run contentHash mismatch");
            }
        }

        private static QualificationRun create(
            DomainGenericDiscoveryQualification.QualificationReport report,
            String profileCatalogJson,
            String profileCatalogHash,
            List<String> cleanRunFingerprints
        ) {
            List<String> fingerprints = List.copyOf(cleanRunFingerprints);
            boolean identical = fingerprints.size() >= CLEAN_RUN_COUNT
                && fingerprints.stream().distinct().count() == 1L;
            String hash = hash(
                profileCatalogHash,
                report.contentHash(),
                fingerprints,
                identical,
                report.status().name(),
                report.domainGenericClaimAuthorized(),
                report.autonomousCampaignClaimAuthorized(),
                report.proofStatus(),
                report.externalNoveltyStatus(),
                report.promotionStatus(),
                report.publicEvidenceStatus());
            return new QualificationRun(
                SCHEMA,
                profileCatalogHash,
                report.contentHash(),
                fingerprints,
                identical,
                report.status().name(),
                report.domainGenericClaimAuthorized(),
                report.autonomousCampaignClaimAuthorized(),
                report.proofStatus(),
                report.externalNoveltyStatus(),
                report.promotionStatus(),
                report.publicEvidenceStatus(),
                hash,
                profileCatalogJson,
                report);
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("profileCatalogHash", profileCatalogHash)
                .property("qualificationReportHash", qualificationReportHash)
                .stringArray("cleanRunFingerprints", cleanRunFingerprints)
                .property("cleanRunsIdentical", cleanRunsIdentical)
                .property("domainGenericQualificationStatus",
                    domainGenericQualificationStatus)
                .property("domainGenericClaimAuthorized",
                    domainGenericClaimAuthorized)
                .property("autonomousCampaignClaimAuthorized",
                    autonomousCampaignClaimAuthorized)
                .property("proofStatus", proofStatus)
                .property("externalNoveltyStatus", externalNoveltyStatus)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }

        private static String hash(
            String profileCatalogHash,
            String qualificationReportHash,
            List<String> cleanRunFingerprints,
            boolean cleanRunsIdentical,
            String domainGenericQualificationStatus,
            boolean domainGenericClaimAuthorized,
            boolean autonomousCampaignClaimAuthorized,
            String proofStatus,
            String externalNoveltyStatus,
            String promotionStatus,
            String publicEvidenceStatus
        ) {
            return AutonomousResearchBriefV2.hash(
                SCHEMA
                    + "\nprofileCatalogHash=" + profileCatalogHash
                    + "\nqualificationReportHash=" + qualificationReportHash
                    + "\ncleanRunFingerprints=" + cleanRunFingerprints
                    + "\ncleanRunsIdentical=" + cleanRunsIdentical
                    + "\ndomainGenericQualificationStatus="
                        + domainGenericQualificationStatus
                    + "\ndomainGenericClaimAuthorized="
                        + domainGenericClaimAuthorized
                    + "\nautonomousCampaignClaimAuthorized="
                        + autonomousCampaignClaimAuthorized
                    + "\nproofStatus=" + proofStatus
                    + "\nexternalNoveltyStatus=" + externalNoveltyStatus
                    + "\npromotionStatus=" + promotionStatus
                    + "\npublicEvidenceStatus=" + publicEvidenceStatus);
        }
    }

    private static String requireSha256(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be SHA-256");
        }
        return value;
    }
}
