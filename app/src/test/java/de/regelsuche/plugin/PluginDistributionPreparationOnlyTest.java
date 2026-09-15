package de.regelsuche.plugin;

import static de.regelsuche.plugin.PluginDistributionClientTest.jar;
import static de.regelsuche.plugin.PluginDistributionClientTest.publish;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.plugin.PluginCheckpointAuthority.AcceptedState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PluginDistributionPreparationOnlyTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"INSTALL", "REMOVE", "ROLLBACK"})
    void directActivationIsRejectedBeforeConsultingTheReaderOrPreparingFiles(String action) throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var publication = publish(fixture, "1.0.0", jar("one"), 1, "", fixture.publishers);
            var reads = new AtomicInteger();
            PluginDistributionClient.StateReader reader = () -> {
                reads.incrementAndGet();
                throw new IOException("external reader must not be consulted by direct activation");
            };
            Path packages = directory.resolve("packages");
            var client = new PluginDistributionClient(packages, "community", fixture.roots,
                reader, transport, limits());
            List<String> before = retainedPaths(packages);

            var failure = assertThrows(IllegalStateException.class, () -> {
                switch (action) {
                    case "INSTALL" -> client.install(publication.sources(), request());
                    case "REMOVE" -> client.remove();
                    case "ROLLBACK" -> client.rollback("sha256:" + "a".repeat(64));
                    default -> throw new AssertionError("unknown test action");
                }
            });
            assertEquals("operation-aware client requires transaction activation", failure.getMessage());
            assertEquals(0, reads.get(), "invalid direct activation must not read the remote authority");
            assertEquals(before, retainedPaths(packages), "invalid direct activation must not prepare a generation");
        }
    }

    @Test
    void explicitPreparationAndActiveReadsRemainAvailableWithoutDirectActivation() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var publication = publish(fixture, "1.0.0", jar("one"), 1, "", fixture.publishers);
            var reads = new AtomicInteger();
            PluginDistributionClient.StateReader reader = () -> {
                reads.incrementAndGet();
                return AcceptedState.empty();
            };
            Path packages = directory.resolve("packages");
            var client = new PluginDistributionClient(packages, "community", fixture.roots,
                reader, transport, limits());
            var prepared = client.prepareInstall(publication.sources(), request());
            assertEquals(AcceptedState.empty(), prepared.expected());
            assertEquals("INSTALL", prepared.evidence().operation());
            assertEquals(1, prepared.evidence().artifacts().size());
            Path manifest = packages.resolve("generations")
                .resolve(prepared.evidence().contentHash().substring(7)).resolve("installation.json");
            assertEquals(prepared.evidence().toCanonicalJson(), Files.readString(manifest));
            assertTrue(client.active().isEmpty(), "preparation alone must not activate its retained generation");
            assertEquals(2, reads.get());
        }
    }

    private static List<String> retainedPaths(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.map(root::relativize).map(Path::toString).sorted().toList();
        }
    }

    private static PluginDistributionTransport transport(PluginDistributionFixtures fixture) {
        return new PluginDistributionTransport(Set.of(fixture.origin), Duration.ofSeconds(2),
            Duration.ofSeconds(3), false, fixture.tls);
    }

    private static PluginDistributionClient.Limits limits() {
        return new PluginDistributionClient.Limits(65536, 65536, 1048576, 16, 64);
    }

    private static PluginArtifactResolver.ResolutionRequest request() {
        return PluginArtifactResolver.ResolutionRequest.exact("installation", PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN,
            "example", "1.0.0", "0.5.0", "1", List.of("algebra"));
    }
}
