package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.domain.DomainDiscoveryExport;
import de.regelsuche.discovery.domain.DomainDiscoveryExportVerifier;
import de.regelsuche.discovery.domain.DomainDiscoveryExportVerifier.VerifiedDomainExport;
import de.regelsuche.release.DomainGenericDiscoveryQualification.QualificationReport;
import de.regelsuche.release.DomainGenericDiscoveryQualification.QualificationStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DomainGenericQualificationRunnerTest {
    private final DomainGenericQualificationRunner runner =
        new DomainGenericQualificationRunner();
    private final DomainDiscoveryExportVerifier verifier =
        new DomainDiscoveryExportVerifier();
    private Path output;
    private DomainGenericQualificationRunner.QualificationRun run;

    @BeforeAll
    void executeReferenceQualification() {
        output = Path.of("build", "reports", "domain-generic-qualification");
        run = runner.run(output);
    }

    @Test
    void qualifiesTwoDistinctDomainsWithoutBroadeningOtherClaims()
            throws Exception {
        QualificationReport report = run.report();

        assertEquals(QualificationStatus.READY, report.status());
        assertTrue(report.domainGenericClaimAuthorized());
        assertFalse(report.autonomousCampaignClaimAuthorized());
        assertEquals(3, report.cleanRunCount());
        assertTrue(report.cleanRunsIdentical());
        assertEquals(List.of(
            "expression-rewrite",
            "integer-sequence-finite-difference"),
            report.domains().stream().map(item -> item.domainId()).toList());
        assertTrue(report.checks().stream().allMatch(item -> item.passed()));
        assertTrue(report.blockers().isEmpty());
        assertEquals("NOT_EVALUATED", report.proofStatus());
        assertEquals("NOT_EVALUATED", report.externalNoveltyStatus());
        assertEquals("NOT_EVALUATED", report.promotionStatus());
        assertEquals("NOT_EVALUATED", report.publicEvidenceStatus());
        assertFalse(run.autonomousCampaignClaimAuthorized());

        for (String file : List.of(
                "profile-catalog.json",
                "qualification-report.json",
                "qualification-run.json")) {
            assertTrue(Files.isRegularFile(output.resolve(file)), file);
            assertTrue(Files.size(output.resolve(file)) > 0L, file);
        }
        for (int cleanRun = 1; cleanRun <= 3; cleanRun++) {
            for (String domain : List.of("expression", "sequence")) {
                Path directory = output.resolve(
                    "runs/run-" + cleanRun + "/" + domain);
                assertTrue(Files.isRegularFile(directory.resolve("domain.json")));
                assertTrue(Files.isRegularFile(directory.resolve("evidence.json")));
                assertTrue(Files.isRegularFile(
                    directory.resolve("lifecycle-handoff.json")));
                assertTrue(Files.isRegularFile(
                    directory.resolve("export-manifest.json")));
                assertTrue(Files.isRegularFile(output.resolve(
                    "runs/run-" + cleanRun
                        + "/verification-receipts/" + domain + ".json")));
            }
        }
    }

    @Test
    void duplicateSingleDomainCannotQualify() {
        VerifiedDomainExport expression = verifier.requireVerified(
            output.resolve("runs/run-1/expression"));
        QualificationReport blocked = new DomainGenericDiscoveryQualification()
            .evaluate(List.of(expression, expression), 3, true);

        assertEquals(QualificationStatus.BLOCKED, blocked.status());
        assertFalse(blocked.domainGenericClaimAuthorized());
        assertTrue(blocked.blockers().contains(
            "AT_LEAST_TWO_DISTINCT_DOMAINS"));
        assertTrue(blocked.blockers().contains(
            "DISTINCT_MATHEMATICAL_STATE_TYPES"));
        assertTrue(blocked.blockers().contains(
            "NON_EXPRESSION_DOMAIN_RETAINED"));
    }

    @Test
    void refutedSecondDomainCannotQualify(@TempDir Path tempDir) {
        VerifiedDomainExport expression = verifier.requireVerified(
            output.resolve("runs/run-1/expression"));
        Path sequenceDirectory = tempDir.resolve("refuted-sequence");
        new DomainDiscoveryExport().write(
            sequenceDirectory,
            DomainGenericQualificationRunner.refutedSequenceEvidence());
        VerifiedDomainExport refuted = verifier.requireVerified(sequenceDirectory);

        QualificationReport blocked = new DomainGenericDiscoveryQualification()
            .evaluate(List.of(expression, refuted), 3, true);

        assertEquals(QualificationStatus.BLOCKED, blocked.status());
        assertTrue(blocked.blockers().contains(
            "CONFIRMED_CANDIDATES_WITH_CERTIFICATES"));
        assertFalse(blocked.domainGenericClaimAuthorized());
    }

    @Test
    void fewerThanThreeCleanRunsFailClosed() {
        VerifiedDomainExport expression = verifier.requireVerified(
            output.resolve("runs/run-1/expression"));
        VerifiedDomainExport sequence = verifier.requireVerified(
            output.resolve("runs/run-1/sequence"));

        QualificationReport blocked = new DomainGenericDiscoveryQualification()
            .evaluate(List.of(expression, sequence), 2, true);

        assertEquals(QualificationStatus.BLOCKED, blocked.status());
        assertEquals(List.of("THREE_CLEAN_MULTI_DOMAIN_RUNS"),
            blocked.blockers());
    }

    @Test
    void reportAndRunHashesRejectTampering() {
        QualificationReport report = run.report();
        assertThrows(IllegalArgumentException.class, () ->
            new QualificationReport(
                report.schema(),
                report.profile(),
                report.status(),
                report.domainGenericClaimAuthorized(),
                report.autonomousCampaignClaimAuthorized(),
                report.cleanRunCount(),
                report.cleanRunsIdentical(),
                report.domains(),
                report.checks(),
                report.blockers(),
                report.proofStatus(),
                report.externalNoveltyStatus(),
                report.promotionStatus(),
                report.publicEvidenceStatus(),
                "sha256:" + "0".repeat(64)));
    }
}
