package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.release.ReleaseReadinessMatrix.ProfileStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReleaseReadinessRunnerTest {
    private final ReleaseReadinessRunner runner = new ReleaseReadinessRunner();
    private ReleaseReadinessRunner.ReleaseRun run;

    @BeforeAll
    void evaluateRealCampaignEvidence() {
        run = runner.run();
    }

    @Test
    void reportsReadyLowerProfilesButBlocksAutonomyClaimFailClosed()
            throws Exception {
        var matrix = run.matrix();

        assertEquals(ProfileStatus.READY, matrix.result(
            ReleaseEvidenceProfile.SEARCH_REPRODUCIBILITY).status());
        assertEquals(ProfileStatus.BLOCKED, matrix.result(
            ReleaseEvidenceProfile.HIDDEN_RULE_REDISCOVERY).status());
        assertEquals(ProfileStatus.READY, matrix.result(
            ReleaseEvidenceProfile.OPEN_TARGET_DISCOVERY).status());
        assertEquals(ProfileStatus.BLOCKED, matrix.result(
            ReleaseEvidenceProfile.AUTONOMOUS_CAMPAIGN).status());
        assertEquals(ProfileStatus.BLOCKED, matrix.result(
            ReleaseEvidenceProfile.EXTERNAL_NOVELTY_REVIEW).status());

        assertFalse(matrix.result(
            ReleaseEvidenceProfile.SEARCH_REPRODUCIBILITY)
            .autonomyClaimAuthorized());
        assertFalse(matrix.result(
            ReleaseEvidenceProfile.OPEN_TARGET_DISCOVERY)
            .autonomyClaimAuthorized());
        assertFalse(matrix.autonomyClaimAuthorized());
        assertFalse(run.autonomousCampaignReady());

        assertEquals(List.of(
            "DOMAIN_GENERIC_SEARCH_INTERFACE",
            "FRESH_HOLDOUTS_AT_LEAST_ONE_HUNDRED",
            "HELD_OUT_FAMILY_OR_CLUSTER",
            "PAIRED_HELD_OUT_UTILITY"),
            matrix.result(ReleaseEvidenceProfile.AUTONOMOUS_CAMPAIGN)
                .blockers());
        assertEquals(List.of(
            "EXTERNAL_NOVELTY_REVIEWED",
            "PUBLIC_EVIDENCE_REVIEWED"),
            matrix.result(ReleaseEvidenceProfile.EXTERNAL_NOVELTY_REVIEW)
                .blockers());

        var evidence = run.evidence();
        assertEquals(3, evidence.cleanRunCount());
        assertTrue(evidence.cleanRunsIdentical());
        assertTrue(evidence.targetFree());
        assertEquals(2, evidence.seedFamilyCount());
        assertEquals(12, evidence.observationCount());
        assertEquals(11, evidence.alphaDistinctSupport());
        assertEquals(6, evidence.configuredFreshHoldouts());
        assertEquals(6, evidence.executedFreshHoldouts());
        assertEquals(4, evidence.counterexampleStrategyCount());
        assertEquals(0, evidence.refutingHoldouts());
        assertEquals(0, evidence.counterexamplesFound());
        assertEquals(0, evidence.mandatorySkippedWorkCount());
        assertEquals("NOVEL_WITHIN_PROJECT", evidence.projectNoveltyStatus());
        assertEquals("SYMBOLICALLY_VERIFIED", evidence.symbolicProofStatus());
        assertEquals("NOT_EVALUATED", evidence.externalNoveltyStatus());
        assertFalse(evidence.pairedHeldOutUtilityEvaluated());
        assertFalse(evidence.domainGenericSearchInterfaceReady());

        Path output = Path.of("build", "reports", "release-readiness");
        runner.write(output, run);
        for (String file : List.of(
                "profiles.json",
                "evidence-summary.json",
                "release-readiness-report.json",
                "release-readiness-run.json",
                "campaign/production-campaign-manifest.json")) {
            Path artifact = output.resolve(file);
            assertTrue(Files.isRegularFile(artifact), file);
            assertTrue(Files.size(artifact) > 0L, file);
        }
    }

    @Test
    void matrixAndCatalogAreCanonicalAndDoNotPromoteBlockedEvidence() {
        var second = ReleaseReadinessMatrix.evaluate(run.evidence());

        assertEquals(run.matrix().contentHash(), second.contentHash());
        assertEquals(run.matrix().toCanonicalJson(), second.toCanonicalJson());
        assertEquals(ReleaseEvidenceProfile.catalogJson(), run.profileCatalogJson());
        assertEquals(1, java.util.Arrays.stream(ReleaseEvidenceProfile.values())
            .filter(ReleaseEvidenceProfile::authorizesAutonomyClaim)
            .count());
        assertTrue(run.matrix().toCanonicalJson().contains(
            "\"autonomousCampaignStatus\":\"BLOCKED\""));
        assertTrue(run.matrix().toCanonicalJson().contains(
            "\"promotionStatus\":\"NOT_EVALUATED\""));
        assertTrue(run.matrix().toCanonicalJson().contains(
            "\"publicEvidenceStatus\":\"NOT_EVALUATED\""));
    }

    @Test
    void missingCampaignEvidenceIsRejectedBeforeProfileEvaluation() {
        assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousCampaignReleaseEvidence.from(List.of()));
        assertThrows(
            NullPointerException.class,
            () -> ReleaseReadinessMatrix.evaluate(null));
    }
}
