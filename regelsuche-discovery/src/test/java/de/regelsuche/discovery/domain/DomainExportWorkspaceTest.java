package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import de.regelsuche.discovery.domain.DomainDiscoveryExport.ArtifactRole;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DomainExportWorkspaceTest {
    @TempDir Path directory;
    private final JsonMapper json = new JsonMapper();

    @Test void retainsOriginalExportAuthorityAndAllTypedWorkDimensions() throws Exception {
        var evidence = sequence("observed=1,4,9,16;holdout=25,36");
        var snapshot = export(evidence);
        var workspace = assertDoesNotThrow(() -> DomainExportWorkspace.fromVerified(snapshot));
        var view = json.readTree(workspace.toCanonicalJson());
        assertEquals(snapshot.manifest().contentHash(), workspace.runId());
        assertEquals(snapshot.verification().manifestByteHash(), view.path("verification").path("manifestByteHash").asText());
        assertEquals(snapshot.verification().contentHash(), view.path("verification").path("contentHash").asText());
        assertEquals(5, view.path("evidence").path("resources").size());
        assertEquals(json.readTree(evidence.toCanonicalJson()), view.path("evidence"));
        assertEquals(evidence.seed().contentHash(), view.path("inputHash").asText());
        assertFalse(view.has("configuredWork"));
        assertFalse(view.has("informationTrack"));
        for (String role : new String[]{"RULE_RADAR", "PROOF_OBLIGATIONS", "EXTERNAL_NOVELTY"}) {
            assertTrue(view.path("artifacts").findValues("role").stream().anyMatch(value -> value.asText().equals(role)));
            for (var item : view.path("artifacts")) if (item.path("role").asText().equals(role)) {
                assertEquals("NOT_PRODUCED", item.path("status").asText());
            }
        }
        assertEquals("NOT_EVALUATED", view.path("evidence").path("proofStatus").asText());
        assertEquals("NOT_EVALUATED", view.path("evidence").path("externalNoveltyStatus").asText());
    }

    @Test void loadingAndReplayUseOnlyTheVerifiedDefensiveBytes() throws Exception {
        var expected = sequence("observed=1,4,9,16;holdout=25,36");
        new DomainDiscoveryExport().write(directory, expected);
        Path manifest = directory.resolve(DomainDiscoveryExport.MANIFEST_FILE_NAME);
        Files.writeString(manifest, Files.readString(manifest) + "\n");
        byte[] originalManifest = Files.readAllBytes(manifest);
        var snapshot = new DomainDiscoveryExportVerifier().requireVerified(directory);
        Files.writeString(manifest, "{}");
        Files.writeString(directory.resolve("evidence.json"), "{}");
        var workspace = assertDoesNotThrow(() -> DomainExportWorkspace.fromVerified(snapshot));
        assertArrayEquals(originalManifest, workspace.originalManifestBytes());
        byte[] exposed = workspace.originalManifestBytes(); exposed[0] = 0;
        assertArrayEquals(originalManifest, workspace.originalManifestBytes());
        assertArrayEquals(expected.toCanonicalJson().getBytes(StandardCharsets.UTF_8), workspace.originalArtifactBytes(ArtifactRole.DISCOVERY_EVIDENCE));
        assertEquals(expected.toCanonicalJson(), workspace.replay().toCanonicalJson());
        assertEquals(expected.toCanonicalJson(), workspace.evidence().toCanonicalJson());
    }

    @Test void refutedSourcesKeepTheirOriginalCounterexamplesAndNoCertificate() throws Exception {
        var evidence = sequence("observed=1,4,9,16;holdout=26");
        var workspace = assertDoesNotThrow(() -> DomainExportWorkspace.fromVerified(export(evidence)));
        assertEquals(DomainDiscoveryEvidence.Outcome.REFUTED, workspace.evidence().outcome());
        assertNull(workspace.evidence().certificate());
        assertEquals(evidence.candidateAttempts(), workspace.evidence().candidateAttempts());
        assertEquals(evidence.toCanonicalJson(), workspace.replay().toCanonicalJson());
    }

    @Test void rehashedFalseExecutionEvidenceCannotPassReplay() throws Exception {
        var source = sequence("observed=1,4,9,16;holdout=25,36");
        var states = new ArrayList<>(source.states());
        var first = states.getFirst();
        states.set(0, new DomainDiscoveryEvidence.StateTrace(first.sequence(), first.stateHash(), first.canonicalState(),
            first.depth(), first.objectiveScore() + 1, first.candidateReady(), first.parentStateHash(), first.actionId(), first.objectiveMetrics()));
        var altered = new DomainDiscoveryEvidence(source.campaignId(), source.descriptor(), source.seed(), source.budget(), source.outcome(),
            states, source.transitions(), source.candidateAttempts(), source.resources(), source.selectedCandidateHash(), source.certificate(), source.domainEvidence());
        var workspace = assertDoesNotThrow(() -> DomainExportWorkspace.fromVerified(export(altered)));
        assertThrows(IllegalStateException.class, workspace::replay);
    }

    @Test void foreignDomainAndSemanticallyForgedSourceHashesFailClosed() throws Exception {
        var domain = new ExpressionRewriteDiscoveryDomain("x");
        var foreign = new DomainDiscoveryRunner().run("foreign-export", domain,
            DiscoverySeed.create("source", domain.domainId(), "x + 0", "public-test"), budget()).evidence();
        assertThrows(IllegalArgumentException.class, () -> DomainExportWorkspace.fromVerified(export(foreign)));

        var source = sequence("observed=1,4,9,16;holdout=25,36");
        var manifest = new DomainDiscoveryExport().write(directory, source);
        byte[] altered = source.toCanonicalJson().replace("public-test", "forged-source-reference").getBytes(StandardCharsets.UTF_8);
        rewriteArtifact(manifest, ArtifactRole.DISCOVERY_EVIDENCE, altered);
        var structurallyVerified = new DomainDiscoveryExportVerifier().requireVerified(directory);
        assertThrows(IllegalArgumentException.class, () -> DomainExportWorkspace.fromVerified(structurallyVerified));
    }

    @Test void sourceNamesDoNotCollapseDistinctSequenceInputs() throws Exception {
        var first = assertDoesNotThrow(() -> DomainExportWorkspace.fromVerified(export(sequence("observed=1,4,9,16;holdout=25,36"))));
        var second = assertDoesNotThrow(() -> DomainExportWorkspace.fromVerified(export(sequence("observed=1,4,9,16;holdout=26"))));
        assertNotEquals(first.runId(), second.runId());
        assertNotEquals(first.evidence().seed().contentHash(), second.evidence().seed().contentHash());
    }

    @Test void loadingRetainsSourcesOutsideReplayAdmissionWithoutIssuingReplayAuthority() throws Exception {
        var domain = new FiniteDifferenceSequenceDomain();
        var source = new DomainDiscoveryRunner().run("source-with-larger-recorded-budget", domain,
            DiscoverySeed.create("source", domain.domainId(), "observed=1,4,9,16;holdout=25,36", "public-test"),
            new DiscoveryBudget(9, 16, 32, 8, 8, 32)).evidence();
        var workspace = DomainExportWorkspace.fromVerified(export(source));
        assertEquals(source.toCanonicalJson(), workspace.evidence().toCanonicalJson());
        assertFalse(workspace.replaySupported());
        var view = json.readTree(workspace.toCanonicalJson());
        for (var role : view.path("artifacts")) if (role.path("role").asText().equals("PATH_REPLAY")) {
            assertEquals("NOT_PRODUCED", role.path("status").asText());
            assertTrue(role.path("targetContentHash").isNull());
        }
        assertThrows(IllegalStateException.class, workspace::replay);
    }

    private DomainDiscoveryExportVerifier.VerifiedDomainExport export(DomainDiscoveryEvidence evidence) {
        new DomainDiscoveryExport().write(directory, evidence);
        return new DomainDiscoveryExportVerifier().requireVerified(directory);
    }

    private void rewriteArtifact(DomainDiscoveryExport.DomainExportManifest manifest, ArtifactRole role, byte[] bytes) throws Exception {
        Files.write(directory.resolve(role.fileName()), bytes);
        var artifacts = manifest.artifacts().stream().map(value -> value.role() == role
            ? new DomainDiscoveryExport.ExportArtifact(value.fileName(), role, value.sourceContentHash(),
                DomainCanonical.sha256(new String(bytes, StandardCharsets.UTF_8)), bytes.length) : value).toList();
        var changed = DomainDiscoveryExport.DomainExportManifest.create(manifest.campaignId(), manifest.domainId(), manifest.domainRevision(),
            manifest.domainDescriptorHash(), manifest.discoveryEvidenceHash(), manifest.lifecycleHandoffHash(), artifacts);
        Files.writeString(directory.resolve(DomainDiscoveryExport.MANIFEST_FILE_NAME), changed.toCanonicalJson());
    }

    static DomainDiscoveryEvidence sequence(String payload) {
        var domain = new FiniteDifferenceSequenceDomain();
        return new DomainDiscoveryRunner().run("same-export-name", domain,
            DiscoverySeed.create("same-seed-name", domain.domainId(), payload, "public-test"), budget()).evidence();
    }

    static DiscoveryBudget budget() { return new DiscoveryBudget(4, 16, 32, 8, 8, 32); }
}
