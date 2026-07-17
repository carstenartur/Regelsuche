package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import de.regelsuche.discovery.domain.DomainDiscoveryExport.ArtifactRole;
import de.regelsuche.discovery.domain.DomainDiscoveryExport.DomainExportManifest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DomainDiscoveryExportTest {
    private final DomainDiscoveryExport exporter = new DomainDiscoveryExport();

    @Test
    void commitsExpressionAndSequenceRunsThroughTheSameManifestContract()
            throws Exception {
        DomainDiscoveryEvidence expression = expressionEvidence();
        DomainDiscoveryEvidence sequence = sequenceEvidence();
        Path root = Path.of("build", "reports", "domain-discovery-export");

        DomainExportManifest expressionManifest = exporter.write(
            root.resolve("expression"), expression);
        DomainExportManifest sequenceManifest = exporter.write(
            root.resolve("sequence"), sequence);

        assertExport(root.resolve("expression"), expression, expressionManifest);
        assertExport(root.resolve("sequence"), sequence, sequenceManifest);
        assertEquals(
            expressionManifest.artifacts().stream().map(item -> item.role()).toList(),
            sequenceManifest.artifacts().stream().map(item -> item.role()).toList());
        assertEquals(
            List.of(
                ArtifactRole.DOMAIN_DESCRIPTOR,
                ArtifactRole.DISCOVERY_EVIDENCE,
                ArtifactRole.LIFECYCLE_HANDOFF),
            expressionManifest.artifacts().stream()
                .map(item -> item.role())
                .sorted()
                .toList());
        assertFalse(expressionManifest.domainId().equals(sequenceManifest.domainId()));
    }

    @Test
    void repeatedExportIsByteIdenticalAndLeavesNoTemporaryFiles(
        @TempDir Path tempDir
    ) throws Exception {
        DomainDiscoveryEvidence evidence = sequenceEvidence();
        Path output = tempDir.resolve("sequence-export");

        DomainExportManifest first = exporter.write(output, evidence);
        Map<String, byte[]> firstBytes = retainedBytes(output);
        Files.writeString(
            output.resolve(DomainDiscoveryExport.MANIFEST_FILE_NAME),
            "stale-manifest\n",
            StandardCharsets.UTF_8);
        DomainExportManifest second = exporter.write(output, evidence);
        Map<String, byte[]> secondBytes = retainedBytes(output);

        assertEquals(first, second);
        assertEquals(firstBytes.keySet(), secondBytes.keySet());
        for (String name : firstBytes.keySet()) {
            assertArrayEquals(firstBytes.get(name), secondBytes.get(name));
        }
        try (var files = Files.list(output)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString()
                .endsWith(".tmp")));
        }
    }

    @Test
    void manifestRejectsMissingRolesAndMismatchedRootHashes(
        @TempDir Path tempDir
    ) {
        DomainExportManifest valid = exporter.write(
            tempDir.resolve("expression-export"), expressionEvidence());

        assertThrows(IllegalArgumentException.class, () ->
            new DomainExportManifest(
                valid.schema(),
                valid.campaignId(),
                valid.domainId(),
                valid.domainRevision(),
                "sha256:" + "0".repeat(64),
                valid.discoveryEvidenceHash(),
                valid.lifecycleHandoffHash(),
                valid.artifacts(),
                valid.commitProtocol(),
                valid.contentHash()));
        assertThrows(IllegalArgumentException.class, () ->
            DomainExportManifest.create(
                valid.campaignId(),
                valid.domainId(),
                valid.domainRevision(),
                valid.domainDescriptorHash(),
                valid.discoveryEvidenceHash(),
                valid.lifecycleHandoffHash(),
                valid.artifacts().stream()
                    .filter(item -> item.role() != ArtifactRole.LIFECYCLE_HANDOFF)
                    .toList()));
    }

    private static void assertExport(
        Path directory,
        DomainDiscoveryEvidence evidence,
        DomainExportManifest manifest
    ) throws Exception {
        DiscoveryLifecycleHandoff handoff = DiscoveryLifecycleHandoff.from(evidence);
        assertEquals(evidence.campaignId(), manifest.campaignId());
        assertEquals(evidence.descriptor().domainId(), manifest.domainId());
        assertEquals(evidence.descriptor().revision(), manifest.domainRevision());
        assertEquals(evidence.descriptor().contentHash(),
            manifest.domainDescriptorHash());
        assertEquals(evidence.contentHash(), manifest.discoveryEvidenceHash());
        assertEquals(handoff.contentHash(), manifest.lifecycleHandoffHash());
        assertEquals(DomainDiscoveryExport.COMMIT_PROTOCOL,
            manifest.commitProtocol());
        assertEquals(3, manifest.artifacts().size());

        Map<ArtifactRole, String> expectedContent = Map.of(
            ArtifactRole.DOMAIN_DESCRIPTOR,
            evidence.descriptor().toCanonicalJson(),
            ArtifactRole.DISCOVERY_EVIDENCE,
            evidence.toCanonicalJson(),
            ArtifactRole.LIFECYCLE_HANDOFF,
            handoff.toCanonicalJson());
        for (var artifact : manifest.artifacts()) {
            Path path = directory.resolve(artifact.fileName());
            String retained = Files.readString(path, StandardCharsets.UTF_8);
            assertEquals(expectedContent.get(artifact.role()), retained);
            assertEquals(DomainCanonical.sha256(retained), artifact.byteHash());
            assertEquals(
                retained.getBytes(StandardCharsets.UTF_8).length,
                artifact.byteLength());
        }
        assertEquals(
            manifest.toCanonicalJson(),
            Files.readString(
                directory.resolve(DomainDiscoveryExport.MANIFEST_FILE_NAME),
                StandardCharsets.UTF_8));

        String handoffJson = expectedContent.get(ArtifactRole.LIFECYCLE_HANDOFF);
        String manifestJson = manifest.toCanonicalJson();
        for (String forbidden : List.of(
                "payload",
                "canonicalState",
                "seedExpression",
                "selectedExpression",
                "sequenceTerms",
                "states",
                "path")) {
            assertFalse(handoffJson.contains("\"" + forbidden + "\""));
            assertFalse(manifestJson.contains("\"" + forbidden + "\""));
        }
        assertTrue(handoffJson.contains(
            "\"sourceEvidenceHash\":\"" + evidence.contentHash() + "\""));
        assertTrue(handoffJson.contains(
            "\"domainContractHash\":\""
                + evidence.descriptor().contentHash() + "\""));
    }

    private static Map<String, byte[]> retainedBytes(Path directory)
            throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (var files = Files.list(directory)) {
            for (Path path : files.sorted().toList()) {
                if (Files.isRegularFile(path)) {
                    result.put(path.getFileName().toString(), Files.readAllBytes(path));
                }
            }
        }
        return result;
    }

    private static DomainDiscoveryEvidence expressionEvidence() {
        var domain = new ExpressionRewriteDiscoveryDomain("x");
        return new DomainDiscoveryRunner().run(
            "generic-domain-expression-export",
            domain,
            DiscoverySeed.create(
                "expression-export",
                domain.domainId(),
                "x + 0",
                "generic-domain-export/v1"),
            new DiscoveryBudget(2, 20, 100, 80, 5, 20)).evidence();
    }

    private static DomainDiscoveryEvidence sequenceEvidence() {
        var domain = new FiniteDifferenceSequenceDomain();
        return new DomainDiscoveryRunner().run(
            "generic-domain-sequence-export",
            domain,
            DiscoverySeed.create(
                "sequence-export",
                domain.domainId(),
                "observed=1,4,9,16;holdout=25,36",
                "generic-domain-export/v1"),
            new DiscoveryBudget(4, 20, 20, 10, 5, 20)).evidence();
    }
}
