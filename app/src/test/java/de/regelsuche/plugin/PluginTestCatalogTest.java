package de.regelsuche.plugin;

import static de.regelsuche.plugin.PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN;
import static de.regelsuche.plugin.PluginArtifactIndex.ArtifactKind.KNOWLEDGE_PACK;
import static de.regelsuche.plugin.PluginArtifactIndex.ArtifactKind.RULE_PACKAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.plugin.PluginArtifactIndex.Dependency;
import de.regelsuche.plugin.PluginArtifactIndex.Entry;
import de.regelsuche.plugin.PluginArtifactResolver.ResolutionRequest;
import de.regelsuche.plugin.PluginArtifactResolver.ResolutionStep;
import de.regelsuche.plugin.PluginDistributionClient.Sources;
import de.regelsuche.plugin.PluginTrustStore.ArtifactRevocation;
import de.regelsuche.plugin.PluginTrustStoreRevisionVerifier.ChainCheckpoint;
import de.regelsuche.plugin.PluginTrustStoreRevisionVerifier.VerifiedTrustStoreRevision;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginTestCatalogTest {
    private static final int METADATA_LIMIT = 65536;
    @TempDir Path directory;

    @Test
    void discoversAnExplicitlyPinnedHttpsCatalogAcrossThreeImmutableTrustStates() throws Exception {
        try (var catalog = new PluginTestCatalog(); var transport = catalog.transport()) {
            var sample = publishSample(catalog);
            var genesisSources = sample.genesis().sources();
            assertEquals("https", genesisSources.indexUri().getScheme());
            assertEquals("127.0.0.1", genesisSources.indexUri().getHost());
            assertNotEquals(genesisSources.indexUri(), sample.next().sources().indexUri());
            assertNotEquals(genesisSources.trustRevisionUri(), sample.next().sources().trustRevisionUri());

            var first = assertDoesNotThrow(() -> refresh(catalog.roots(), transport, genesisSources, null, METADATA_LIMIT));
            assertEquals(1L, first.trust().checkpoint().sequence());
            assertEquals(Set.of(JAVA_PLUGIN, RULE_PACKAGE, KNOWLEDGE_PACK), first.index().entries().stream()
                .map(Entry::kind).collect(Collectors.toSet()));
            assertEquals(List.of("1.0.0"), versions(first, JAVA_PLUGIN));
            assertEquals(List.of("1.0.0"), versions(first, RULE_PACKAGE));
            assertEquals(List.of("1.0.0"), versions(first, KNOWLEDGE_PACK));
            var genesisResponses = new LinkedHashMap<URI, byte[]>();
            for (URI uri : List.of(genesisSources.trustStoreUri(), genesisSources.trustRevisionUri(),
                    genesisSources.indexUri(), genesisSources.indexSignatureUri())) {
                genesisResponses.put(uri, transport.download(uri, METADATA_LIMIT));
            }

            var second = refresh(catalog.roots(), transport, sample.next().sources(),
                first.trust().checkpoint(), METADATA_LIMIT);
            assertEquals(2L, second.trust().checkpoint().sequence());
            assertEquals(List.of("2.0.0", "1.1.0", "1.0.1", "1.0.0"), versions(second, JAVA_PLUGIN));
            var resolver = new PluginArtifactResolver();
            var resolved = resolver.resolve(second.index(),
                ResolutionRequest.latestCompatible("browse", JAVA_PLUGIN, "example", "0.5.1", "1", List.of("algebra")));
            assertEquals(List.of("library", "example"), resolved.plan().stream().map(ResolutionStep::componentId).toList());
            assertEquals("1.0.1", resolved.plan().getLast().version());
            assertEquals(List.of("no-core-compatible-version:0.5.1"), resolver.resolve(second.index(),
                ResolutionRequest.exact("browse", JAVA_PLUGIN, "example", "2.0.0", "0.5.1", "1", List.of())).blockers());
            assertEquals(List.of("no-api-compatible-version:1"), resolver.resolve(second.index(),
                ResolutionRequest.exact("browse", JAVA_PLUGIN, "example", "1.1.0", "0.5.1", "1", List.of())).blockers());

            var chosen = second.index().componentVersions(JAVA_PLUGIN, "example").stream()
                .filter(entry -> entry.version().equals("1.0.1")).findFirst().orElseThrow();
            assertEquals(resolved.plan().getLast().provenanceUri(), chosen.provenanceUri());
            var provenance = PluginArtifactProvenance.read(
                transport.download(URI.create(chosen.provenanceUri()), METADATA_LIMIT));
            provenance.requireTrusted(chosen, second.trust().trustStore());
            assertEquals("publisher", provenance.publisherId());
            assertEquals("https://source.example.test/project", provenance.sourceUri());
            assertEquals("source-revision-1", provenance.sourceRevision());

            var third = refresh(catalog.roots(), transport, sample.last().sources(),
                second.trust().checkpoint(), METADATA_LIMIT);
            assertEquals(3L, third.trust().checkpoint().sequence());
            assertNotEquals(first.trust().trustStore(), second.trust().trustStore());
            assertNotEquals(second.trust().trustStore(), third.trust().trustStore());
            assertThrows(SecurityException.class, () -> provenance.requireTrusted(chosen, third.trust().trustStore()));
            for (var response : genesisResponses.entrySet()) {
                assertArrayEquals(response.getValue(), transport.download(response.getKey(), METADATA_LIMIT));
            }
        }
    }

    @Test
    void rejectsReplayForkGapsAndTransportFailures() throws Exception {
        try (var catalog = new PluginTestCatalog(); var transport = catalog.transport()) {
            var sample = publishSample(catalog);
            var first = assertDoesNotThrow(() -> refresh(catalog.roots(), transport,
                sample.genesis().sources(), null, METADATA_LIMIT));
            var checkpoint = first.trust().checkpoint();
            assertTrue(assertThrows(SecurityException.class, () -> refresh(catalog.roots(), transport,
                sample.genesis().sources(), checkpoint, METADATA_LIMIT)).getMessage().contains("REPLAYED_REVISION"));
            assertTrue(assertThrows(SecurityException.class, () -> refresh(catalog.roots(), transport,
                sample.last().sources(), checkpoint, METADATA_LIMIT)).getMessage().contains("SEQUENCE_GAP"));
            var fork = catalog.publishRevision(2, "sha256:" + "e".repeat(64), catalog.publishers(),
                sample.next().index().entries());
            assertTrue(assertThrows(SecurityException.class, () -> refresh(catalog.roots(), transport, fork.sources(),
                checkpoint, METADATA_LIMIT)).getMessage().contains("PREVIOUS_HASH_MISMATCH"));

            var next = sample.next().sources();
            var oversized = assertThrows(IOException.class, () -> refresh(catalog.roots(), transport, next, checkpoint, 64));
            assertEquals("transport response exceeds byte limit",
                assertInstanceOf(IOException.class, oversized.getCause()).getMessage());
            var redirect = catalog.redirectTo(next.indexUri());
            var redirected = assertThrows(IOException.class, () -> refresh(catalog.roots(), transport,
                withIndex(next, redirect, next.indexContentHash()), checkpoint, METADATA_LIMIT));
            assertEquals("transport requires HTTP 200; received 302",
                assertInstanceOf(IOException.class, redirected.getCause()).getMessage());
            var offOrigin = URI.create("https://localhost:" + catalog.origin().getPort() + next.indexUri().getRawPath());
            assertThrows(SecurityException.class, () -> refresh(catalog.roots(), transport,
                withIndex(next, offOrigin, next.indexContentHash()), checkpoint, METADATA_LIMIT));
            assertThrows(SecurityException.class, () -> refresh(catalog.roots(), transport,
                withIndex(next, next.indexUri(), first.index().contentHash()), checkpoint, METADATA_LIMIT));

            var second = refresh(catalog.roots(), transport, next, checkpoint, METADATA_LIMIT);
            assertEquals(sample.next().revision().contentHash(), second.trust().checkpoint().revisionHash());
        }
    }

    @Test
    void closingBorrowedCatalogKeepsTheOwnersServerRunning() throws Exception {
        try (var owner = new PluginDistributionFixtures()) {
            var catalog = new PluginTestCatalog(owner);
            try (var transport = catalog.transport()) {
                Sources sources;
                try (catalog) {
                    var entry = javaRelease(catalog, "1.0.0", "1", "0.5.0", List.of());
                    sources = catalog.publishRevision(1, "", owner.publishers, List.of(entry)).sources();
                }
                var selection = assertDoesNotThrow(() -> refresh(owner.roots, transport, sources, null, METADATA_LIMIT));
                assertEquals(List.of("1.0.0"), versions(selection, JAVA_PLUGIN));
            }
        }
    }

    private BrowseSnapshot refresh(PluginTrustStore pinnedRoots, PluginDistributionTransport transport,
            Sources pin, ChainCheckpoint previous, long maximumMetadataBytes) throws IOException {
        // Every explicit refresh makes at most four bounded metadata requests and accepts one chain successor.
        Path scratch = Files.createTempDirectory(directory, "browse-");
        var store = PluginTrustStore.load(fetch(transport, pin.trustStoreUri(), scratch.resolve("store.json"),
            maximumMetadataBytes));
        var revision = PluginTrustStoreRevision.read(fetch(transport, pin.trustRevisionUri(),
            scratch.resolve("revision.json"), maximumMetadataBytes));
        if (!PluginTestCatalog.TRUST_DOMAIN.equals(revision.trustDomainId())) {
            throw new SecurityException("unexpected catalog trust domain");
        }
        var trust = new PluginTrustStoreRevisionVerifier(pinnedRoots).requireTrusted(store, revision, previous);
        var index = new PluginArtifactIndexVerifier(store).requireTrusted(
            fetch(transport, pin.indexUri(), scratch.resolve("index.json"), maximumMetadataBytes),
            fetch(transport, pin.indexSignatureUri(), scratch.resolve("index.sig.json"), maximumMetadataBytes)).index();
        if (!pin.indexId().equals(index.indexId()) || !pin.indexRevision().equals(index.revision())
                || !pin.indexContentHash().equals(index.contentHash())) {
            throw new SecurityException("catalog index differs from source pin");
        }
        return new BrowseSnapshot(index, trust);
    }

    private static Path fetch(PluginDistributionTransport transport, URI uri, Path destination, long maximum)
            throws IOException {
        byte[] bytes = transport.download(uri, maximum);
        PluginDistributionJson.validate(bytes);
        return Files.write(destination, bytes);
    }

    private static Sources withIndex(Sources pin, URI indexUri, String contentHash) {
        return new Sources(pin.trustStoreUri(), pin.trustRevisionUri(), indexUri, pin.indexSignatureUri(),
            pin.indexId(), pin.indexRevision(), contentHash);
    }

    private static List<String> versions(BrowseSnapshot selected, PluginArtifactIndex.ArtifactKind kind) {
        return selected.index().componentVersions(kind, "example").stream().map(Entry::version).toList();
    }

    private static Sample publishSample(PluginTestCatalog catalog) throws Exception {
        var library = catalog.publishArtifact(JAVA_PLUGIN, "library", "1.0.0", "1", "0.5.0", "", List.of(),
            List.of(), PluginDistributionClientTest.jar("library"));
        var dependencies = List.of(new Dependency(JAVA_PLUGIN, "library", "=1.0.0", false));
        var initial = javaRelease(catalog, "1.0.0", "1", "0.5.0", dependencies);
        var rules = catalog.publishArtifact(RULE_PACKAGE, "example", "1.0.0", "1", "0.5.0", "", List.of(),
            List.of(), "profile example:\n  enable_tags:\n    - algebra\n".getBytes(StandardCharsets.UTF_8));
        var knowledge = catalog.publishArtifact(KNOWLEDGE_PACK, "example", "1.0.0", "1", "0.5.0", "", List.of(),
            List.of(), "{\"facts\":[]}\n".getBytes(StandardCharsets.UTF_8));
        var genesis = catalog.publishRevision(1, "", catalog.publishers(), List.of(initial, library, rules, knowledge));

        var update = javaRelease(catalog, "1.0.1", "1", "0.5.0", dependencies);
        var otherApi = javaRelease(catalog, "1.1.0", "2", "0.5.0", dependencies);
        var futureCore = javaRelease(catalog, "2.0.0", "1", "0.6.0", dependencies);
        var entries = List.of(initial, update, otherApi, futureCore, library, rules, knowledge);
        var nextStore = new PluginTrustStore(PluginTrustStore.SCHEMA, catalog.publishers().keys(),
            List.of(new ArtifactRevocation(PluginDistributionFixtures.hash("unlisted artifact"), "fixture revocation")));
        var next = catalog.publishRevision(2, genesis.revision().contentHash(), nextStore, entries);
        var finalStore = new PluginTrustStore(PluginTrustStore.SCHEMA, nextStore.keys(),
            List.of(nextStore.revokedArtifacts().getFirst(), new ArtifactRevocation(update.artifactSha256(),
                "fixture revocation of the selected release")));
        var last = catalog.publishRevision(3, next.revision().contentHash(), finalStore, entries);
        return new Sample(genesis, next, last);
    }

    private static Entry javaRelease(PluginTestCatalog catalog, String version, String api, String minimumCore,
            List<Dependency> dependencies) throws Exception {
        return catalog.publishArtifact(JAVA_PLUGIN, "example", version, api, minimumCore, "", List.of("algebra"),
            dependencies, PluginDistributionClientTest.jar("example-" + version));
    }

    private record BrowseSnapshot(PluginArtifactIndex index, VerifiedTrustStoreRevision trust) { }
    private record Sample(PluginTestCatalog.Publication genesis, PluginTestCatalog.Publication next,
        PluginTestCatalog.Publication last) { }
}
