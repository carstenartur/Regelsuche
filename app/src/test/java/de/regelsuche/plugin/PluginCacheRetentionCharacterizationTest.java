package de.regelsuche.plugin;

import static de.regelsuche.plugin.PluginDistributionClientTest.jar;
import static de.regelsuche.plugin.PluginDistributionClientTest.publish;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.plugin.PluginCheckpointAuthority.AcceptedState;
import de.regelsuche.plugin.PluginCheckpointTransactions.Decision;
import de.regelsuche.plugin.PluginCheckpointTransactions.Operation;
import de.regelsuche.plugin.PluginCheckpointTransactions.Outcome;
import de.regelsuche.plugin.PluginCheckpointTransactions.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Characterizes the pre-existing aggregate retention gap, not a quota fix.
 * The small successful reproductions do not establish any finite upper bound.
 * Future quota admission tests must be added separately with an explicit limit.
 */
class PluginCacheRetentionCharacterizationTest {
    @TempDir Path directory;

    @Test
    void rejectedLegacyInstallsRetainDistinctInactiveGenerations() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var authority = new PluginDistributionClientTest.TestAuthority();
            authority.reject = true;
            var client = new PluginDistributionClient(packages(), "community", fixture.roots,
                authority, transport, limits());
            long previousBytes = 0;
            for (int attempt = 1; attempt <= 3; attempt++) {
                String version = attempt + ".0.0";
                var publication = publish(fixture, version, jar("rejected-" + attempt),
                    1, "", fixture.publishers);
                assertThrows(SecurityException.class,
                    () -> client.install(publication.sources(), request(version)));
                assertEquals(AcceptedState.empty(), authority.read());
                assertTrue(client.active().isEmpty());
                assertEquals(attempt, generationCount());
                long retainedBytes = generationBytes();
                assertTrue(retainedBytes > previousBytes, "a rejected distinct generation adds retained bytes");
                previousBytes = retainedBytes;
            }
        }
    }

    @Test
    void repeatedUnknownSubmissionsAlsoRetainDistinctGenerationsWithoutConfirmedActiveState() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var authority = new UncertainAuthority(scope(fixture));
            var client = new PluginDistributionTransactions(packages(), fixture.roots, authority, transport, limits());
            long previousBytes = 0;
            for (int attempt = 1; attempt <= 3; attempt++) {
                String version = attempt + ".0.0";
                var publication = publish(fixture, version, jar("unknown-" + attempt),
                    1, "", fixture.publishers);
                var result = client.install("unknown-" + attempt, publication.sources(), request(version));
                assertEquals(Outcome.OUTCOME_UNKNOWN, result.outcome());
                assertEquals("NOT_CONFIRMED", result.localEvidenceStatus());
                assertEquals(attempt, authority.submissions);
                assertEquals(AcceptedState.empty(), authority.read());
                assertTrue(client.active().isEmpty());
                assertEquals(attempt, generationCount());
                long retainedBytes = generationBytes();
                assertTrue(retainedBytes > previousBytes, "unknown operations still consume local storage");
                previousBytes = retainedBytes;
            }
        }
    }

    @Test
    void aLostCommitAcknowledgementKeepsEveryFileRecoverableAfterRestartAndReplay() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            byte[] artifact = jar("committed-but-answer-lost");
            var publication = publish(fixture, "1.0.0", artifact, 1, "", fixture.publishers);
            var authority = new PluginDistributionTransactionsTest.Ledger(scope(fixture));
            authority.loseAnswer = true;
            var client = new PluginDistributionTransactions(packages(), fixture.roots, authority, transport, limits());
            var uncertain = client.install("install", publication.sources(), request("1.0.0"));
            assertEquals(Outcome.OUTCOME_UNKNOWN, uncertain.outcome());
            String retainedHash = uncertain.decision().operation().update().installationHash();
            assertEquals(retainedHash, authority.read().installationHash());
            Path generation = packages().resolve("generations/" + retainedHash.substring(7));
            Map<String, String> originalFiles = fileHashes(generation);
            assertTrue(originalFiles.containsKey("installation.json"));
            assertEquals(1, generationCount());

            authority.loseAnswer = false;
            fixture.responses.clear(); // Recovery and replay must not redownload or repeat preparation.
            var restarted = new PluginDistributionTransactions(packages(), fixture.roots, authority, transport, limits());
            var recovered = restarted.recover("install");
            assertEquals(Outcome.COMMITTED, recovered.outcome());
            assertEquals("AVAILABLE", recovered.localEvidenceStatus());
            assertEquals(retainedHash, recovered.installation().contentHash());
            assertArrayEquals(artifact, restarted.readArtifact(
                recovered.installation().artifacts().getFirst().identityHash()));
            assertEquals(recovered.decision(),
                restarted.install("install", publication.sources(), request("1.0.0")).decision());
            assertEquals(originalFiles, fileHashes(generation));
            assertEquals(1, generationCount(), "replay must not consume storage for another generation");
        }
    }

    private Path packages() { return directory.resolve("packages"); }

    private long generationCount() throws IOException {
        try (var children = Files.list(packages().resolve("generations"))) {
            return children.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).count();
        }
    }

    private long generationBytes() throws IOException {
        long result = 0;
        try (var paths = Files.walk(packages().resolve("generations"))) {
            for (Path file : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                result = Math.addExact(result, Files.size(file));
            }
        }
        return result;
    }

    private static Map<String, String> fileHashes(Path generation) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        try (var paths = Files.walk(generation)) {
            for (Path file : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                hashes.put(generation.relativize(file).toString(),
                    PluginArtifactVerifier.sha256(Files.readAllBytes(file)));
            }
        }
        return hashes;
    }

    private static PluginDistributionClient.Limits limits() {
        return new PluginDistributionClient.Limits(65536, 65536, 1048576, 16, 64);
    }

    private static PluginDistributionTransport transport(PluginDistributionFixtures fixture) {
        return new PluginDistributionTransport(Set.of(fixture.origin), Duration.ofSeconds(2),
            Duration.ofSeconds(3), false, fixture.tls);
    }

    private static Scope scope(PluginDistributionFixtures fixture) {
        return new Scope("operator-slot", "community", PluginDistributionJson.hash(fixture.roots.toCanonicalJson()));
    }

    private static PluginArtifactResolver.ResolutionRequest request(String version) {
        return PluginArtifactResolver.ResolutionRequest.exact("installation",
            PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN, "example", version, "0.5.0", "1", List.of("algebra"));
    }

    /** Failure-injection model, not evidence about actual PostgreSQL durability. */
    private static final class UncertainAuthority implements PluginCheckpointTransactions {
        private final Scope scope;
        private int submissions;
        private UncertainAuthority(Scope scope) { this.scope = scope; }
        public Scope scope() { return scope; }
        public AcceptedState read() { return AcceptedState.empty(); }
        public Optional<Decision> lookup(String id) { return Optional.empty(); }
        public Decision submit(Operation operation) throws IOException {
            submissions++;
            throw new IOException("injected lost submission response");
        }
    }
}
