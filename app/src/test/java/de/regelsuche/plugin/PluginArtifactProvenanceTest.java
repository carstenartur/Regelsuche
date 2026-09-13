package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginArtifactProvenanceTest {
    @Test
    void verifiesOnlyMatchingEntryAndExistingPublisherKey() throws Exception {
        try (var fixture = new PluginDistributionFixtures()) {
            var entry = fixture.entry("1.0.0", new byte[] {1, 2});
            var provenance = signed(fixture, entry);
            assertDoesNotThrow(() -> provenance.requireTrusted(entry, fixture.publishers));
            assertThrows(SecurityException.class, () -> provenance.requireTrusted(
                fixture.entry("1.0.0", new byte[] {3, 4}), fixture.publishers));
            assertThrows(SecurityException.class,
                () -> provenance.requireTrusted(entry, PluginTrustStore.empty()));
            var revoked = new PluginTrustStore(PluginTrustStore.SCHEMA,
                List.of(PluginDistributionFixtures.key("publisher", fixture.publisherKey,
                    PluginTrustStore.KeyStatus.REVOKED)), List.of());
            assertThrows(SecurityException.class, () -> provenance.requireTrusted(entry, revoked));
            var tampered = PluginArtifactProvenance.read(provenance.toCanonicalJson()
                .replace("source-revision-1", "source-revision-2").getBytes(StandardCharsets.UTF_8));
            assertThrows(SecurityException.class, () -> tampered.requireTrusted(entry, fixture.publishers));
        }
    }

    @Test
    void rejectsAmbiguousJsonAndPreservesCanonicalBytes() throws Exception {
        try (var fixture = new PluginDistributionFixtures()) {
            var provenance = signed(fixture, fixture.entry("1.0.0", new byte[] {1}));
            String json = provenance.toCanonicalJson();
            assertEquals(json, PluginArtifactProvenance.read(json.getBytes(StandardCharsets.UTF_8))
                .toCanonicalJson());
            for (String corrupt : List.of(json + "{}", json.replace("{", "{\"schema\":\"bad\","),
                    json.replace("\"schema\":", "\"unknown\":\"extra\",\"schema\":"))) {
                assertThrows(IllegalArgumentException.class,
                    () -> PluginArtifactProvenance.read(corrupt.getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    static PluginArtifactProvenance signed(PluginDistributionFixtures fixture,
            PluginArtifactIndex.Entry entry) throws Exception {
        var unsigned = envelope(entry, Base64.getEncoder().encodeToString(new byte[64]));
        return envelope(entry, PluginDistributionFixtures.sign(fixture.publisherKey, unsigned.signedPayload()));
    }

    private static PluginArtifactProvenance envelope(PluginArtifactIndex.Entry entry, String signature) {
        return new PluginArtifactProvenance(PluginArtifactProvenance.SCHEMA, entry.identityHash(),
            entry.artifactSha256(), entry.provenanceUri(), "https://source.example.test/project",
            "source-revision-1", PluginDistributionFixtures.hash("source archive"),
            PluginDistributionFixtures.hash("build recipe"), "publisher", "key-1", "Ed25519", signature);
    }
}
