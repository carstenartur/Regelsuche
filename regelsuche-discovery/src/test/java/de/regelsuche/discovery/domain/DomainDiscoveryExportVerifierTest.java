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
import de.regelsuche.discovery.domain.DomainDiscoveryExport.ExportArtifact;
import de.regelsuche.discovery.domain.DomainDiscoveryExportVerifier.ExportVerificationException;
import de.regelsuche.discovery.domain.DomainDiscoveryExportVerifier.VerifiedDomainExport;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DomainDiscoveryExportVerifierTest {
    private final DomainDiscoveryExport exporter = new DomainDiscoveryExport();
    private final DomainDiscoveryExportVerifier verifier =
        new DomainDiscoveryExportVerifier();

    @Test
    void verifiesExpressionAndSequenceExportsAsImmutableSnapshots()
            throws Exception {
        Path root = Path.of(
            "build", "reports", "domain-discovery-export-verification");
        verifyAndRetain(root.resolve("expression"), expressionEvidence());
        verifyAndRetain(root.resolve("sequence"), sequenceEvidence());
    }

    @Test
    void rejectsTamperedMissingUnexpectedAndOversizedArtifacts(
        @TempDir Path tempDir
    ) throws Exception {
        Path tampered = export(tempDir.resolve("tampered"), expressionEvidence());
        Files.writeString(
            tampered.resolve("evidence.json"),
            " ",
            StandardCharsets.UTF_8,
            StandardOpenOption.APPEND);
        ExportVerificationException tamperedFailure = assertThrows(
            ExportVerificationException.class,
            () -> verifier.requireVerified(tampered));
        assertTrue(tamperedFailure.getMessage().contains("byte"));

        Path missing = export(tempDir.resolve("missing"), sequenceEvidence());
        Files.delete(missing.resolve("lifecycle-handoff.json"));
        assertThrows(ExportVerificationException.class, () ->
            verifier.requireVerified(missing));

        Path unexpected = export(tempDir.resolve("unexpected"), expressionEvidence());
        Files.writeString(
            unexpected.resolve("unlisted.json"),
            "{}",
            StandardCharsets.UTF_8);
        assertThrows(ExportVerificationException.class, () ->
            verifier.requireVerified(unexpected));

        Path oversized = export(tempDir.resolve("oversized"), sequenceEvidence());
        DomainDiscoveryExportVerifier tiny =
            new DomainDiscoveryExportVerifier(64L, 64L);
        assertThrows(ExportVerificationException.class, () ->
            tiny.requireVerified(oversized));
    }

    @Test
    void rejectsSymlinksIdentitySubstitutionAndDuplicateJsonFields(
        @TempDir Path tempDir
    ) throws Exception {
        Path symlinked = export(tempDir.resolve("symlinked"), expressionEvidence());
        Path descriptor = symlinked.resolve("domain.json");
        Path descriptorCopy = symlinked.resolveSibling("descriptor-copy.json");
        Files.copy(descriptor, descriptorCopy);
        Files.delete(descriptor);
        Files.createSymbolicLink(descriptor, descriptorCopy);
        assertThrows(ExportVerificationException.class, () ->
            verifier.requireVerified(symlinked));

        Path realParent = tempDir.resolve("real-parent");
        export(realParent.resolve("nested"), sequenceEvidence());
        Path linkedParent = tempDir.resolve("linked-parent");
        Files.createSymbolicLink(linkedParent, realParent);
        ExportVerificationException ancestryFailure = assertThrows(
            ExportVerificationException.class,
            () -> verifier.requireVerified(linkedParent.resolve("nested")));
        assertTrue(ancestryFailure.getMessage().contains("symbolic link"));

        Path substituted = tempDir.resolve("substituted");
        DomainExportManifest substitutedManifest = exporter.write(
            substituted, expressionEvidence());
        byte[] substitutedEvidence = Files.readString(
                substituted.resolve("evidence.json"))
            .replace(
                "generic-domain-expression-export",
                "generic-domain-substituted-export")
            .getBytes(StandardCharsets.UTF_8);
        rewriteArtifact(
            substituted,
            substitutedManifest,
            ArtifactRole.DISCOVERY_EVIDENCE,
            substitutedEvidence);
        ExportVerificationException identityFailure = assertThrows(
            ExportVerificationException.class,
            () -> verifier.requireVerified(substituted));
        assertTrue(identityFailure.getMessage().contains("campaignId"));

        Path duplicate = tempDir.resolve("duplicate");
        DomainExportManifest duplicateManifest = exporter.write(
            duplicate, sequenceEvidence());
        byte[] duplicateEvidence = Files.readString(duplicate.resolve("evidence.json"))
            .replaceFirst(
                "\\{",
                "{\"schema\":\"regelsuche.domain-discovery-evidence/v1\",")
            .getBytes(StandardCharsets.UTF_8);
        rewriteArtifact(
            duplicate,
            duplicateManifest,
            ArtifactRole.DISCOVERY_EVIDENCE,
            duplicateEvidence);
        assertThrows(ExportVerificationException.class, () ->
            verifier.requireVerified(duplicate));
    }

    @Test
    void rejectsNullsAndInvalidBounds() {
        assertThrows(NullPointerException.class, () ->
            verifier.requireVerified(null));
        assertThrows(IllegalArgumentException.class, () ->
            new DomainDiscoveryExportVerifier(0L, 1L));
        assertThrows(IllegalArgumentException.class, () ->
            new DomainDiscoveryExportVerifier(1L, 0L));
        assertThrows(IllegalArgumentException.class, () ->
            new DomainDiscoveryExportVerifier(
                (long) Integer.MAX_VALUE + 1L,
                1L));
    }

    private void verifyAndRetain(
        Path directory,
        DomainDiscoveryEvidence evidence
    ) throws Exception {
        Files.deleteIfExists(directory.resolve("verification.json"));
        exporter.write(directory, evidence);
        VerifiedDomainExport verified = verifier.requireVerified(directory);

        assertEquals(evidence.campaignId(), verified.manifest().campaignId());
        assertEquals(evidence.descriptor().domainId(), verified.manifest().domainId());
        assertEquals(
            DomainDiscoveryExportVerifier.Verification.VERIFIED,
            verified.verification().identityBindingStatus());
        assertEquals(
            DomainDiscoveryExportVerifier.Verification.NOT_EVALUATED,
            verified.verification().mathematicalValidationStatus());
        assertEquals(3, verified.verification().verifiedArtifactCount());
        assertEquals(
            verified.manifest().contentHash(),
            verified.verification().manifestContentHash());

        for (ArtifactRole role : ArtifactRole.values()) {
            String fileName = switch (role) {
                case DOMAIN_DESCRIPTOR -> "domain.json";
                case DISCOVERY_EVIDENCE -> "evidence.json";
                case LIFECYCLE_HANDOFF -> "lifecycle-handoff.json";
            };
            byte[] expected = Files.readAllBytes(directory.resolve(fileName));
            byte[] retained = verified.artifactBytes(role);
            assertArrayEquals(expected, retained);
            retained[0] ^= 0x01;
            assertArrayEquals(expected, verified.artifactBytes(role));
        }

        String handoff = new String(
            verified.artifactBytes(ArtifactRole.LIFECYCLE_HANDOFF),
            StandardCharsets.UTF_8);
        for (String forbidden : List.of(
                "payload",
                "canonicalState",
                "seedExpression",
                "selectedExpression",
                "sequenceTerms",
                "states",
                "path")) {
            assertFalse(handoff.contains("\"" + forbidden + "\""));
        }
        assertTrue(handoff.contains(
            "\"sourceEvidenceHash\":\"" + evidence.contentHash() + "\""));

        Path receipt = directory.resolve("verification.json");
        Files.writeString(
            receipt,
            verified.verification().toCanonicalJson(),
            StandardCharsets.UTF_8);
        assertEquals(
            verified.verification().toCanonicalJson(),
            Files.readString(receipt));

        var constructors = VerifiedDomainExport.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()));
    }

    private static void rewriteArtifact(
        Path directory,
        DomainExportManifest manifest,
        ArtifactRole role,
        byte[] replacementBytes
    ) throws Exception {
        ExportArtifact original = manifest.artifacts().stream()
            .filter(item -> item.role() == role)
            .findFirst()
            .orElseThrow();
        Files.write(directory.resolve(original.fileName()), replacementBytes);
        String replacementHash = DomainCanonical.sha256(
            new String(replacementBytes, StandardCharsets.UTF_8));
        List<ExportArtifact> artifacts = manifest.artifacts().stream()
            .map(item -> item.role() == role
                ? new ExportArtifact(
                    item.fileName(),
                    item.role(),
                    item.sourceContentHash(),
                    replacementHash,
                    replacementBytes.length)
                : item)
            .toList();
        DomainExportManifest replacement = DomainExportManifest.create(
            manifest.campaignId(),
            manifest.domainId(),
            manifest.domainRevision(),
            manifest.domainDescriptorHash(),
            manifest.discoveryEvidenceHash(),
            manifest.lifecycleHandoffHash(),
            artifacts);
        Files.writeString(
            directory.resolve(DomainDiscoveryExport.MANIFEST_FILE_NAME),
            replacement.toCanonicalJson(),
            StandardCharsets.UTF_8);
    }

    private Path export(Path directory, DomainDiscoveryEvidence evidence) {
        exporter.write(directory, evidence);
        return directory;
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
                "generic-domain-export-verification/v1"),
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
                "generic-domain-export-verification/v1"),
            new DiscoveryBudget(4, 20, 20, 10, 5, 20)).evidence();
    }
}
