package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import de.regelsuche.discovery.domain.DiscoveryDomain.DomainPayload;
import de.regelsuche.discovery.domain.DomainDiscoveryExport.ArtifactRole;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExpressionRewriteWorkspaceTest {
    @TempDir Path directory;

    @Test void importsPersistsAndReplaysTheRecordedExpressionTarget() throws Exception {
        var source = expression("x + 0", "x");
        var repository = new DomainExportWorkspaceRepository(directory.resolve("retained"));
        var workspace = repository.retain(export(source));
        assertEquals(ExpressionRewriteDiscoveryDomain.DOMAIN_ID, workspace.evidence().descriptor().domainId());
        assertTrue(workspace.replaySupported());
        assertEquals(source.toCanonicalJson(), workspace.replay().toCanonicalJson());
        assertArrayEquals(source.toCanonicalJson().getBytes(StandardCharsets.UTF_8),
            workspace.originalArtifactBytes(ArtifactRole.DISCOVERY_EVIDENCE));
        var reloaded = new DomainExportWorkspaceRepository(directory.resolve("retained")).find(workspace.runId()).orElseThrow();
        assertEquals(workspace.toCanonicalJson(), reloaded.toCanonicalJson());
        assertEquals(source.toCanonicalJson(), reloaded.replay().toCanonicalJson());
    }

    @Test void sameSeedWithDifferentRecordedTargetsKeepsDistinctIdentities() {
        var first = DomainExportWorkspace.fromVerified(export(expression("x + 0", "x")));
        var second = DomainExportWorkspace.fromVerified(export(expression("x + 0", "x + 0")));
        assertEquals(first.evidence().seed(), second.evidence().seed());
        assertNotEquals(first.runId(), second.runId());
        assertNotEquals(first.contentHash(), second.contentHash());
        assertEquals(first.evidence().toCanonicalJson(), first.replay().toCanonicalJson());
        assertEquals(second.evidence().toCanonicalJson(), second.replay().toCanonicalJson());
    }

    @Test void rehashedTargetChangeCannotReproduceTheOriginalExecution() {
        var original = expression("x + 0", "x");
        var values = new LinkedHashMap<>(original.domainEvidence().properties());
        values.put("expectedTarget", "y");
        var changed = withPayload(original, new DomainPayload("expression-rewrite-evidence/v1", values));
        var workspace = DomainExportWorkspace.fromVerified(export(changed));
        assertThrows(IllegalStateException.class, workspace::replay);
        var assessment = DomainExportWorkspace.read(DomainSnapshotAssessment.assess(workspace).toCanonicalJson());
        assertEquals("REPLAY_MISMATCH", assessment.path("replayStatus").asText());
        assertEquals("NOT_EVALUATED", assessment.at("/proof/status").asText());
        assertEquals("NO_VERIFIED_SUBJECT", assessment.at("/externalNovelty/status").asText());
    }

    @Test void missingTargetAndForeignPayloadContractAreRejected() {
        var original = expression("x + 0", "x");
        var values = new LinkedHashMap<>(original.domainEvidence().properties());
        values.remove("expectedTarget");
        assertThrows(IllegalArgumentException.class, () -> DomainExportWorkspace.fromVerified(export(
            withPayload(original, new DomainPayload("expression-rewrite-evidence/v1", values)))));
        assertThrows(IllegalArgumentException.class, () -> DomainExportWorkspace.fromVerified(export(
            withPayload(original, new DomainPayload("expression-rewrite-evidence/v2", original.domainEvidence().properties())))));
    }

    @Test void oversizedSeedOrTargetRemainsReadableWithoutExecutingReplay() {
        for (var source : new DomainDiscoveryEvidence[]{expression("a".repeat(257), "x"), expression("x", "a".repeat(257))}) {
            var workspace = DomainExportWorkspace.fromVerified(export(source));
            assertEquals(source.toCanonicalJson(), workspace.evidence().toCanonicalJson());
            assertFalse(workspace.replaySupported());
            var assessment = DomainExportWorkspace.read(DomainSnapshotAssessment.assess(workspace).toCanonicalJson());
            assertEquals("OUTSIDE_REPLAY_BUDGET", assessment.path("replayStatus").asText());
            assertEquals("NOT_EVALUATED", assessment.at("/ablation/status").asText());
            assertThrows(IllegalStateException.class, workspace::replay);
        }
    }

    @Test void expressionReplayCannotIssueSequenceValidationOrModelProof() {
        var workspace = DomainExportWorkspace.fromVerified(export(expression("x + 0", "x")));
        assertEquals("UNSUPPORTED", DomainExportWorkspace.read(DomainDownstreamValidation.validate(workspace).toCanonicalJson()).path("status").asText());
        assertEquals("UNSUPPORTED", DomainExportWorkspace.read(DomainModelProof.prove(workspace).toCanonicalJson()).path("status").asText());
        var source = DomainExportWorkspace.read(workspace.evidence().toCanonicalJson());
        assertEquals("NOT_EVALUATED", source.path("proofStatus").asText());
        assertEquals("NOT_EVALUATED", source.path("externalNoveltyStatus").asText());
    }

    private DomainDiscoveryExportVerifier.VerifiedDomainExport export(DomainDiscoveryEvidence source) {
        new DomainDiscoveryExport().write(directory.resolve("source"), source);
        return new DomainDiscoveryExportVerifier().requireVerified(directory.resolve("source"));
    }

    private static DomainDiscoveryEvidence expression(String seed, String target) {
        var domain = new ExpressionRewriteDiscoveryDomain(target);
        return new DomainDiscoveryRunner().run("expression-export", domain,
            DiscoverySeed.create("same-seed", domain.domainId(), seed, "public regression control"),
            new DiscoveryBudget(2, 16, 32, 8, 8, 32)).evidence();
    }

    private static DomainDiscoveryEvidence withPayload(DomainDiscoveryEvidence source, DomainPayload payload) {
        return new DomainDiscoveryEvidence(source.campaignId(), source.descriptor(), source.seed(), source.budget(),
            source.outcome(), source.states(), source.transitions(), source.candidateAttempts(), source.resources(),
            source.selectedCandidateHash(), source.certificate(), payload);
    }
}
