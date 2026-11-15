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
            // A completion receipt from an earlier execution cannot cover a partial refresh.
            Files.deleteIfExists(root.resolve("qualification-run.json"));
            for (int run = 1; run <= CLEAN_RUN_COUNT; run++) {
                executeRun(root.resolve("runs/run-" + run));
            }
            QualificationRun result = compose(root);
            write(root.resolve("profile-catalog.json"), result.profileCatalogJson());
            write(root.resolve("qualification-report.json"),
                result.report().toCanonicalJson());
            write(root.resolve("qualification-run.json"),
                result.toCanonicalJson());
            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not execute domain-generic qualification", exception);
        }
    }

    private void executeRun(Path directory)
            throws IOException {
        DomainDiscoveryEvidence expression = expressionEvidence();
        DomainDiscoveryEvidence sequence = sequenceEvidence();
        List<DomainInput> inputs = List.of(
            new DomainInput("expression", expression),
            new DomainInput("sequence", sequence));
        Path receipts = directory.resolve("verification-receipts");
        Files.createDirectories(receipts);
        for (DomainInput input : inputs) {
            Path exportDirectory = directory.resolve(input.name());
            exporter.write(exportDirectory, input.evidence());
            VerifiedDomainExport snapshot = verifier.requireVerified(exportDirectory);
            write(
                receipts.resolve(input.name() + ".json"),
                snapshot.verification().toCanonicalJson());
        }
    }

    /** Reopens all source exports and compares every emitted public artifact with its producer. */
    public QualificationRun verifyRetained(Path root) {
        try {
            QualificationRun result = compose(root);
            requireOriginal(root.resolve("profile-catalog.json"), result.profileCatalogJson());
            requireOriginal(root.resolve("qualification-report.json"), result.report().toCanonicalJson());
            requireOriginal(root.resolve("qualification-run.json"), result.toCanonicalJson());
            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not verify retained domain qualification", exception);
        }
    }

    private QualificationRun compose(Path root) throws IOException {
        List<List<VerifiedDomainExport>> runs = new ArrayList<>();
        for (int index = 1; index <= CLEAN_RUN_COUNT; index++) {
            Path directory = root.resolve("runs/run-" + index);
            List<VerifiedDomainExport> exports = new ArrayList<>();
            for (String name : List.of("expression", "sequence")) {
                VerifiedDomainExport verified = verifier.requireVerified(directory.resolve(name));
                String expectedDomain = name.equals("expression") ? "expression-rewrite" : "integer-sequence-finite-difference";
                if (!expectedDomain.equals(verified.manifest().domainId())) {
                    throw new IllegalArgumentException("Unexpected reference domain at " + directory.resolve(name));
                }
                requireOriginal(directory.resolve("verification-receipts").resolve(name + ".json"),
                    verified.verification().toCanonicalJson());
                exports.add(verified);
            }
            runs.add(List.copyOf(exports));
        }
        String catalog = DomainGenericEvidenceProfile.catalogJson();
        return QualificationRun.create(qualification.evaluate(runs), catalog, AutonomousResearchBriefV2.hash(catalog),
            runs.stream().map(DomainGenericDiscoveryQualification::runFingerprint).toList());
    }

    private static void requireOriginal(Path file, String expected) throws IOException {
        if (Files.isSymbolicLink(file) || Files.size(file) != expected.getBytes(StandardCharsets.UTF_8).length
                || !Files.readString(file, StandardCharsets.UTF_8).equals(expected)) {
            throw new IllegalArgumentException("Retained domain qualification evidence differs: " + file);
        }
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
                    || report.cleanRunCount() != cleanRunFingerprints.size()
                    || report.cleanRunsIdentical() != cleanRunsIdentical
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
