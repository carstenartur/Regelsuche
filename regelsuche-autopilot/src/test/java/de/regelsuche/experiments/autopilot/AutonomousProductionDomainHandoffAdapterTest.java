package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.domain.DiscoveryLifecycleHandoff;
import de.regelsuche.experiments.autopilot.AutonomousProductionGenerationExport.ArtifactRole;
import de.regelsuche.experiments.autopilot.AutonomousProductionGenerationExport.GenerationExportManifest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class AutonomousProductionDomainHandoffAdapterTest {
    @Test
    void adaptsAndCommitsTargetFreeProductionGenerationWithoutDomainObjects()
            throws Exception {
        AutonomousProductionGenerationRunner.GenerationRun run =
            new AutonomousProductionGenerationRunner().runPinned(1);

        DiscoveryLifecycleHandoff handoff =
            new AutonomousProductionDomainHandoffAdapter().adapt(run);

        assertEquals(DiscoveryLifecycleHandoff.SourceKind.PRODUCTION_GENERATION_RUN,
            handoff.sourceKind());
        assertEquals(DiscoveryLifecycleHandoff.Stage.GENERATION, handoff.stage());
        assertEquals(DiscoveryLifecycleHandoff.Disposition.COMPLETED,
            handoff.disposition());
        assertEquals(run.seedCatalog().contentHash(), handoff.inputHash());
        assertEquals(run.contentHash(), handoff.sourceEvidenceHash());
        assertEquals(AutonomousProductionDomainHandoffAdapter.DOMAIN_CONTRACT_HASH,
            handoff.domainContractHash());
        assertTrue(handoff.selectedCandidateHash().isEmpty());
        assertTrue(handoff.certificateHash().isEmpty());
        assertTrue(handoff.resources().stream().allMatch(account ->
            account.configured() == account.executed()
                + account.skipped() + account.remaining()));
        assertEquals(Integer.toString(run.observations().size()),
            handoff.metadata().get("observationCount"));

        String json = handoff.toCanonicalJson();
        assertFalse(json.contains("\"seedExpression\""));
        assertFalse(json.contains("\"selectedExpression\""));
        assertFalse(json.contains("\"states\""));
        assertFalse(json.contains("\"path\""));
        assertTrue(json.contains("\"proofStatus\":\"NOT_EVALUATED\""));
        assertTrue(json.contains("\"promotionStatus\":\"NOT_EVALUATED\""));

        Path evidenceOutput = Path.of(
            "build", "reports", "domain-lifecycle-handoff",
            "production-generation-handoff.json");
        Files.createDirectories(evidenceOutput.getParent());
        Files.writeString(evidenceOutput, json, StandardCharsets.UTF_8);
        assertEquals(json, Files.readString(evidenceOutput));

        Path exportOutput = Path.of(
            "build", "reports", "domain-lifecycle-handoff",
            "production-generation-export");
        AutonomousProductionGenerationExport exporter =
            new AutonomousProductionGenerationExport();
        GenerationExportManifest manifest = exporter.write(exportOutput, run);

        assertEquals(run.contentHash(), manifest.generationRunHash());
        assertEquals(handoff.contentHash(), manifest.lifecycleHandoffHash());
        assertEquals(AutonomousProductionGenerationExport.COMMIT_PROTOCOL,
            manifest.commitProtocol());
        assertEquals(EnumSet.allOf(ArtifactRole.class), manifest.artifacts().stream()
            .map(artifact -> artifact.role())
            .collect(() -> EnumSet.noneOf(ArtifactRole.class),
                EnumSet::add,
                EnumSet::addAll));
        assertEquals(7, manifest.artifacts().size());
        for (var artifact : manifest.artifacts()) {
            Path artifactPath = exportOutput.resolve(artifact.fileName());
            assertTrue(Files.isRegularFile(artifactPath));
            String content = Files.readString(artifactPath, StandardCharsets.UTF_8);
            assertEquals(AutonomousResearchBrief.hash(content), artifact.byteHash());
            assertEquals(content.getBytes(StandardCharsets.UTF_8).length,
                artifact.byteLength());
        }
        assertEquals(json, Files.readString(
            exportOutput.resolve("lifecycle-handoff.json"),
            StandardCharsets.UTF_8));
        assertEquals(manifest.toCanonicalJson(), Files.readString(
            exportOutput.resolve(
                AutonomousProductionGenerationExport.MANIFEST_FILE_NAME),
            StandardCharsets.UTF_8));
        try (var files = Files.list(exportOutput)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString()
                .endsWith(".tmp")));
        }

        GenerationExportManifest repeated = exporter.write(exportOutput, run);
        assertEquals(manifest, repeated);
        assertEquals(manifest.toCanonicalJson(), Files.readString(
            exportOutput.resolve(
                AutonomousProductionGenerationExport.MANIFEST_FILE_NAME),
            StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () ->
            GenerationExportManifest.create(
                run.contentHash(),
                handoff.contentHash(),
                manifest.artifacts().subList(0, 6)));
        assertThrows(IllegalArgumentException.class, () ->
            GenerationExportManifest.create(
                AutonomousResearchBrief.hash("different-generation-run"),
                handoff.contentHash(),
                manifest.artifacts()));

        assertEquals(handoff,
            new AutonomousProductionDomainHandoffAdapter().adapt(run));
    }
}
