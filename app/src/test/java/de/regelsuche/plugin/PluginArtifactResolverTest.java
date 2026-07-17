package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.plugin.PluginArtifactIndex.ArtifactKind;
import de.regelsuche.plugin.PluginArtifactIndex.Dependency;
import de.regelsuche.plugin.PluginArtifactIndex.Entry;
import de.regelsuche.plugin.PluginArtifactResolver.ResolutionReceipt;
import de.regelsuche.plugin.PluginArtifactResolver.ResolutionRequest;
import de.regelsuche.plugin.PluginArtifactResolver.ResolutionStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginArtifactResolverTest {
    private final PluginArtifactResolver resolver = new PluginArtifactResolver();

    @Test
    void resolvesLatestCompatibleDependencyPlanWithoutSideEffects() {
        PluginArtifactIndex index = PluginArtifactIndexFixtures.referenceIndex();
        ResolutionRequest request = ResolutionRequest.latestCompatible(
            "resolve-advanced-tools",
            ArtifactKind.JAVA_PLUGIN,
            "advanced-tools",
            "1.5.0",
            "1",
            List.of("transformations"));

        ResolutionReceipt receipt = resolver.resolve(index, request);

        assertEquals(ResolutionStatus.RESOLVED, receipt.status());
        assertEquals(List.of("core-rules", "advanced-tools"),
            receipt.plan().stream().map(step -> step.componentId()).toList());
        assertEquals(List.of("1.0.0", "1.0.0"),
            receipt.plan().stream().map(step -> step.version()).toList());
        assertEquals(receipt.plan().getLast().identityHash(),
            receipt.rootArtifactIdentityHash());
        assertTrue(receipt.blockers().isEmpty());
        assertEquals(List.of(
            "optional-dependency-unavailable:advanced-tools-1.0.0->"
                + "KNOWLEDGE_PACK/optional-examples@any"), receipt.warnings());
        assertEquals(PluginArtifactResolver.NOT_PERFORMED,
            receipt.networkAccessStatus());
        assertEquals(PluginArtifactResolver.NOT_PERFORMED,
            receipt.installationStatus());
        assertEquals(PluginArtifactResolver.NOT_EVALUATED,
            receipt.trustVerificationStatus());
        assertEquals(receipt, resolver.resolve(index, request));
        assertEquals(receipt.toCanonicalJson(),
            resolver.resolve(index, request).toCanonicalJson());
    }

    @Test
    void exactVersionDoesNotFallBackWhenCoreIsIncompatible() {
        PluginArtifactIndex index = PluginArtifactIndexFixtures.referenceIndex();
        ResolutionReceipt receipt = resolver.resolve(index, ResolutionRequest.exact(
            "resolve-future-exact",
            ArtifactKind.JAVA_PLUGIN,
            "advanced-tools",
            "1.1.0",
            "1.5.0",
            "1",
            List.of()));

        assertEquals(ResolutionStatus.UNRESOLVED, receipt.status());
        assertEquals(List.of("no-core-compatible-version:1.5.0"),
            receipt.blockers());
        assertTrue(receipt.plan().isEmpty());
        assertTrue(receipt.rootArtifactIdentityHash().isEmpty());
    }

    @Test
    void reportsCapabilityAndPublicationBlockersDeterministically() {
        PluginArtifactIndex index = PluginArtifactIndexFixtures.referenceIndex();
        ResolutionReceipt capabilities = resolver.resolve(index,
            ResolutionRequest.latestCompatible(
                "resolve-required-visitor",
                ArtifactKind.JAVA_PLUGIN,
                "advanced-tools",
                "1.5.0",
                "1",
                List.of("visitors")));
        assertEquals(List.of("required-capabilities-unavailable:visitors"),
            capabilities.blockers());

        ResolutionReceipt missing = resolver.resolve(index,
            ResolutionRequest.latestCompatible(
                "resolve-missing-component",
                ArtifactKind.JAVA_PLUGIN,
                "does-not-exist",
                "1.5.0",
                "1",
                List.of()));
        assertEquals(List.of("component-not-published:JAVA_PLUGIN/does-not-exist"),
            missing.blockers());
    }

    @Test
    void blocksConflictingTransitiveDependencyVersions() {
        Entry libraryOne = PluginArtifactIndexFixtures.entry(
            "shared-library-1.0.0",
            ArtifactKind.JAVA_PLUGIN,
            "shared-library",
            "1.0.0",
            "1",
            "1.0.0",
            "",
            List.of(),
            List.of());
        Entry libraryTwo = PluginArtifactIndexFixtures.entry(
            "shared-library-2.0.0",
            ArtifactKind.JAVA_PLUGIN,
            "shared-library",
            "2.0.0",
            "1",
            "1.0.0",
            "",
            List.of(),
            List.of());
        Entry bridge = PluginArtifactIndexFixtures.entry(
            "bridge-1.0.0",
            ArtifactKind.JAVA_PLUGIN,
            "bridge",
            "1.0.0",
            "1",
            "1.0.0",
            "",
            List.of(),
            List.of(new Dependency(
                ArtifactKind.JAVA_PLUGIN,
                "shared-library",
                "=2.0.0",
                false)));
        Entry root = PluginArtifactIndexFixtures.entry(
            "conflicting-root-1.0.0",
            ArtifactKind.JAVA_PLUGIN,
            "conflicting-root",
            "1.0.0",
            "1",
            "1.0.0",
            "",
            List.of(),
            List.of(
                new Dependency(
                    ArtifactKind.JAVA_PLUGIN,
                    "shared-library",
                    "=1.0.0",
                    false),
                new Dependency(
                    ArtifactKind.JAVA_PLUGIN,
                    "bridge",
                    "=1.0.0",
                    false)));
        PluginArtifactIndex index = PluginArtifactIndex.create(
            "conflict-index",
            "1.0.0",
            "curator",
            List.of(root, bridge, libraryTwo, libraryOne));

        ResolutionReceipt receipt = resolver.resolve(index,
            ResolutionRequest.latestCompatible(
                "resolve-conflicting-root",
                ArtifactKind.JAVA_PLUGIN,
                "conflicting-root",
                "1.0.0",
                "1",
                List.of()));

        assertEquals(ResolutionStatus.UNRESOLVED, receipt.status());
        assertTrue(receipt.blockers().stream().anyMatch(blocker ->
            blocker.startsWith(
                "dependency-version-conflict:JAVA_PLUGIN/shared-library:")));
        assertTrue(receipt.plan().isEmpty());
    }

    @Test
    void receiptRejectsTamperingAndRequestRejectsAmbiguousSelectors() {
        PluginArtifactIndex index = PluginArtifactIndexFixtures.referenceIndex();
        ResolutionReceipt receipt = resolver.resolve(index,
            ResolutionRequest.latestCompatible(
                "resolve-tamper-reference",
                ArtifactKind.JAVA_PLUGIN,
                "advanced-tools",
                "1.5.0",
                "1",
                List.of("transformations")));

        assertThrows(IllegalArgumentException.class, () ->
            new ResolutionReceipt(
                receipt.schema(),
                receipt.indexContentHash(),
                receipt.request(),
                receipt.status(),
                receipt.rootArtifactIdentityHash(),
                receipt.plan(),
                receipt.blockers(),
                new ArrayList<>(),
                receipt.networkAccessStatus(),
                receipt.installationStatus(),
                receipt.trustVerificationStatus(),
                receipt.contentHash()));

        assertThrows(IllegalArgumentException.class, () ->
            new ResolutionRequest(
                PluginArtifactResolver.REQUEST_SCHEMA,
                "ambiguous-request",
                ArtifactKind.JAVA_PLUGIN,
                "advanced-tools",
                PluginArtifactResolver.SelectionMode.LATEST_COMPATIBLE,
                "1.0.0",
                "1.5.0",
                "1",
                List.of(),
                PluginArtifactIndexFixtures.hash("not-the-request")));
    }

    @Test
    void resolvedPlanRetainsImmutableDistributionAndProvenanceIdentity() {
        ResolutionReceipt receipt = resolver.resolve(
            PluginArtifactIndexFixtures.referenceIndex(),
            ResolutionRequest.latestCompatible(
                "resolve-provenance",
                ArtifactKind.JAVA_PLUGIN,
                "advanced-tools",
                "1.5.0",
                "1",
                List.of()));

        assertFalse(receipt.plan().getLast().signatureManifestUri().isBlank());
        assertTrue(receipt.plan().stream().allMatch(step ->
            step.artifactUri().startsWith("https://")
                && step.provenanceUri().startsWith("https://")
                && step.artifactSha256().matches("sha256:[0-9a-f]{64}")
                && step.identityHash().matches("sha256:[0-9a-f]{64}")));
    }
}
