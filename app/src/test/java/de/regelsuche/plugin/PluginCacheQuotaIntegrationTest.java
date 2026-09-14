package de.regelsuche.plugin;

import static de.regelsuche.plugin.PluginDistributionClientTest.jar;
import static de.regelsuche.plugin.PluginDistributionClientTest.publish;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.plugin.PluginCheckpointAuthority.AcceptedState;
import de.regelsuche.plugin.PluginCheckpointTransactions.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class PluginCacheQuotaIntegrationTest {
    @TempDir Path temporary;
    private Path packages() { return temporary.resolve("packages"); }
    private static PluginDistributionClient.Limits limits() { return PluginCacheQuotaTest.limits(); }
    private static PluginDistributionTransport transport(PluginDistributionFixtures fixture) {
        return new PluginDistributionTransport(Set.of(fixture.origin), Duration.ofSeconds(2), Duration.ofSeconds(3), false, fixture.tls);
    }
    private static Scope scope(PluginDistributionFixtures f) {
        return new Scope("operator-slot", "community", PluginDistributionJson.hash(f.roots.toCanonicalJson()));
    }
    private static PluginArtifactResolver.ResolutionRequest request(String version) {
        return PluginArtifactResolver.ResolutionRequest.exact("installation", PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN,
            "example", version, "0.5.0", "1", List.of("algebra"));
    }
    private static long bytes(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            long result = 0;
            for (Path p : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                if (!p.equals(root.resolve(".cache-quota")) && !p.equals(root.resolve(".cache-quota.lock")))
                    result = Math.addExact(result, Files.size(p));
            }
            return result;
        }
    }
    private static Map<String, String> hashes(Path directory) throws IOException {
        var result = new TreeMap<String, String>();
        try (var paths = Files.walk(directory)) {
            for (Path p : paths.filter(Files::isRegularFile).toList())
                result.put(directory.relativize(p).toString(), PluginArtifactVerifier.sha256(Files.readAllBytes(p)));
        }
        return result;
    }
    @Test void rejectedAndUnknownSubmissionsEventuallyRefuseMoreDataBeforeCallingAuthority() throws Exception {
        for (boolean unknown : List.of(false, true)) {
            Path root = temporary.resolve(unknown ? "unknown" : "rejected");
            long capacity = 40_000;
            new PluginCacheQuota(capacity, 300).configure(root);
            try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
                var authority = new RefusingAuthority(scope(fixture), unknown);
                var client = new PluginDistributionTransactions(root, fixture.roots, authority, transport, limits());
                boolean full = false;
                for (int attempt = 1; attempt <= 30; attempt++) {
                    String version = attempt + ".0.0";
                    var publication = publish(fixture, version, jar("artifact-" + attempt), 1, "", fixture.publishers);
                    int submitted = authority.submissions;
                    try {
                        var result = client.install("attempt-" + attempt, publication.sources(), request(version));
                        assertEquals(unknown ? Outcome.OUTCOME_UNKNOWN : Outcome.REJECTED, result.outcome());
                        assertEquals(submitted + 1, authority.submissions);
                    } catch (SecurityException quota) {
                        assertTrue(quota.getMessage().contains("quota"), quota.getMessage());
                        assertEquals(submitted, authority.submissions, "capacity failure cannot submit an operation");
                        full = true;
                    }
                    assertEquals(AcceptedState.empty(), authority.read());
                    assertTrue(client.active().isEmpty());
                    assertTrue(bytes(root) <= capacity);
                    if (full) break;
                }
                assertTrue(authority.submissions > 0, "exercise retained rejected/unknown generations first");
                assertTrue(full, "repeated failed installs must eventually stop growing the cache");
            }
        }
    }
    @Test void fullCacheRetainsLostAcknowledgementRecoveryOfflineReplayAndIdenticalGeneration() throws Exception {
        long capacity = 100_000;
        new PluginCacheQuota(capacity, 300).configure(packages());
        try (var f = new PluginDistributionFixtures(); var transport = transport(f)) {
            byte[] artifact = jar("retained");
            var publication = publish(f, "1.0.0", artifact, 1, "", f.publishers);
            var authority = new PluginDistributionTransactionsTest.Ledger(scope(f));
            authority.loseAnswer = true;
            var client = new PluginDistributionTransactions(packages(), f.roots, authority, transport, limits());
            var uncertain = client.install("one", publication.sources(), request("1.0.0"));
            assertEquals(Outcome.OUTCOME_UNKNOWN, uncertain.outcome());
            String hash = uncertain.decision().operation().update().installationHash();
            var store = new PluginInstallationStore(packages(), limits());
            var snapshot = store.load(hash);
            var before = hashes(packages().resolve("generations"));
            try (var filler = store.work()) {
                PluginInstallationStore.write(filler.directory(), "capacity", new byte[Math.toIntExact(capacity - bytes(packages()))]);
                assertEquals(capacity, bytes(packages()));
                assertThrows(SecurityException.class, () -> PluginInstallationStore.write(filler.directory(), "extra", new byte[1]));
                // Existing immutable data can be revalidated without writing its bytes again.
                store.persist(snapshot.evidence(), snapshot.files());
                assertEquals(capacity, bytes(packages()));
                authority.loseAnswer = false;
                f.responses.clear();
                var restarted = new PluginDistributionTransactions(packages(), f.roots, authority, transport, limits());
                var recovered = restarted.recover("one");
                assertEquals(Outcome.COMMITTED, recovered.outcome());
                assertEquals("AVAILABLE", recovered.localEvidenceStatus());
                assertEquals(recovered.decision(), restarted.install("one", publication.sources(), request("1.0.0")).decision());
                assertArrayEquals(artifact, restarted.readArtifact(recovered.installation().artifacts().getFirst().identityHash()));
                assertEquals(before, hashes(packages().resolve("generations")));
                assertEquals(capacity, bytes(packages()));
            }
        }
    }
    @Test void provisionedPolicyIsPersistentAndCannotBeSilentlyEnlarged() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new PluginCacheQuota(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PluginCacheQuota(1, 0));
        var quota = new PluginCacheQuota(128, 20);
        quota.configure(packages()); quota.configure(packages());
        assertThrows(SecurityException.class, () -> new PluginCacheQuota(129, 20).configure(packages()));
        assertEquals(quota, PluginCacheQuota.locked(packages(), () -> PluginCacheQuota.policy(packages())));
        var store = new PluginInstallationStore(packages(), limits());
        for (String invalid : List.of("0", "-1", "9223372036854775808", "01")) {
            Files.writeString(packages().resolve(".cache-quota"), "regelsuche.plugin-cache-quota/v1\n" + invalid + "\n20\n");
            assertThrows(SecurityException.class, store::work);
        }
    }
    /** Explicit failure injection; actual PostgreSQL receipt durability is tested separately. */
    private static final class RefusingAuthority implements PluginCheckpointTransactions {
        private final Scope scope;
        private final boolean unknown;
        private int submissions;
        RefusingAuthority(Scope scope, boolean unknown) { this.scope = scope; this.unknown = unknown; }
        public Scope scope() { return scope; }
        public AcceptedState read() { return AcceptedState.empty(); }
        public Optional<Decision> lookup(String id) { return Optional.empty(); }
        public Decision submit(Operation operation) throws IOException {
            submissions++;
            if (unknown) throw new IOException("injected acknowledgement loss");
            return new Decision(operation, Outcome.REJECTED);
        }
    }
}
