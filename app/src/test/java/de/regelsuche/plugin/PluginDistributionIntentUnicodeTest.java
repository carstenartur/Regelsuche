package de.regelsuche.plugin;

import static de.regelsuche.plugin.PluginDistributionClientTest.jar;
import static de.regelsuche.plugin.PluginDistributionClientTest.publish;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.plugin.PluginCheckpointAuthority.AcceptedState;
import de.regelsuche.plugin.PluginCheckpointTransactions.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Reject raw malformed URI text before JSON escaping can hide it. */
class PluginDistributionIntentUnicodeTest {
    @TempDir Path directory;

    static Stream<Arguments> malformedSources() {
        return IntStream.range(0, 4).boxed().flatMap(index -> Stream.of(
            "" + (char) 0xd800, "" + (char) 0xdc00,
            "" + (char) 0xd800 + 'x', "" + (char) 0xdc00 + (char) 0xd800)
            .map(suffix -> Arguments.of(index, suffix)));
    }

    @ParameterizedTest(name = "source {0}, malformed suffix case {index}")
    @MethodSource("malformedSources")
    void aFreshMalformedIntentIsRejectedBeforeAuthorityAccessOrLocalPreparation(int index, String suffix) throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = new PluginDistributionTransport(
                Set.of(fixture.origin), Duration.ofSeconds(2), Duration.ofSeconds(3), false, fixture.tls)) {
            var original = publish(fixture, "1.0.0", jar("one"), 1, "", fixture.publishers).sources();
            URI[] uris = { original.trustStoreUri(), original.trustRevisionUri(),
                original.indexUri(), original.indexSignatureUri() };
            uris[index] = URI.create(uris[index] + "?q=" + suffix);
            var sources = new PluginDistributionClient.Sources(uris[0], uris[1], uris[2], uris[3],
                original.indexId(), original.indexRevision(), original.indexContentHash());
            var scope = new Scope("operator-slot", "community", PluginDistributionJson.hash(fixture.roots.toCanonicalJson()));
            var lookups = new AtomicInteger();
            var authority = new PluginCheckpointTransactions() {
                public Scope scope() { return scope; }
                public Optional<Decision> lookup(String id) {
                    lookups.incrementAndGet();
                    return Optional.empty(); // A genuinely fresh operation, not an ID collision.
                }
                public AcceptedState read() { throw new AssertionError("must not start preparation"); }
                public Decision submit(Operation operation) { throw new AssertionError("must not submit"); }
            };
            Path packages = directory.resolve("packages");
            var client = new PluginDistributionTransactions(packages, fixture.roots, authority, transport,
                new PluginDistributionClient.Limits(65536, 65536, 1048576, 16, 64));
            var request = PluginArtifactResolver.ResolutionRequest.exact("installation",
                PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN, "example", "1.0.0", "0.5.0", "1", List.of("algebra"));
            assertAll(
                () -> assertThrows(SecurityException.class, () -> client.install("fresh", sources, request)),
                () -> assertEquals(0, lookups.get(), "reject before looking up the operation ID"),
                () -> assertFalse(Files.exists(packages), "reject before opening the local generation store"));
        }
    }


    @ParameterizedTest(name = "valid Unicode in source {0}")
    @ValueSource(ints = {0, 1, 2, 3})
    void validUnicodeKeepsCanonicalIntentBytesAndCanBeReplayed(int index) throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = new PluginDistributionTransport(
                Set.of(fixture.origin), Duration.ofSeconds(2), Duration.ofSeconds(3), false, fixture.tls)) {
            var original = publish(fixture, "1.0.0", jar("one"), 1, "", fixture.publishers).sources();
            URI[] uris = { original.trustStoreUri(), original.trustRevisionUri(),
                original.indexUri(), original.indexSignatureUri() };
            uris[index] = URI.create(uris[index] + "?q=λ🔒");
            var sources = new PluginDistributionClient.Sources(uris[0], uris[1], uris[2], uris[3],
                original.indexId(), original.indexRevision(), original.indexContentHash());
            var scope = new Scope("operator-slot", "community", PluginDistributionJson.hash(fixture.roots.toCanonicalJson()));
            var request = PluginArtifactResolver.ResolutionRequest.exact("installation",
                PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN, "example", "1.0.0", "0.5.0", "1", List.of("algebra"));
            String expectedHash = PluginDistributionJson.hash(PluginDistributionJson.canonical(Map.of(
                "schema", "regelsuche.plugin-distribution-intent/v1", "scope", scope, "action", "INSTALL",
                "inputs", Map.of("sources", sources, "request", request))));
            var client = new PluginDistributionTransactions(directory.resolve("packages"), fixture.roots,
                new PluginDistributionTransactionsTest.Ledger(scope), transport,
                new PluginDistributionClient.Limits(65536, 65536, 1048576, 16, 64));
            var installed = client.install("unicode", sources, request);
            assertEquals(Outcome.COMMITTED, installed.outcome());
            assertEquals(expectedHash, installed.decision().operation().intentHash());
            fixture.responses.clear();
            assertEquals(installed.decision(), client.install("unicode", sources, request).decision());
        }
    }

}
