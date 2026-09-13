package de.regelsuche.plugin;

import static de.regelsuche.plugin.PluginDistributionClientTest.jar;
import static de.regelsuche.plugin.PluginDistributionClientTest.publish;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.plugin.PluginCheckpointAuthority.AcceptedState;
import de.regelsuche.plugin.PluginCheckpointTransactions.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real signed HTTPS generations; the external ledger is a model, not PostgreSQL execution. */
class PluginCheckpointRecoveryReviewTest {
    @TempDir Path directory;

    @Test void restartRecoveryReadsTheRemoteReceiptDespiteAStructurallyBrokenLocalCache() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var publication = publish(fixture, "1.0.0", jar("one"), 1, "", fixture.publishers);
            var ledger = new PluginDistributionTransactionsTest.Ledger(scope(fixture));
            var installed = client(fixture, transport, ledger).install("one", publication.sources(), request());
            assertEquals(Outcome.COMMITTED, installed.outcome());
            Path generations = directory.resolve("packages/generations");
            Files.move(generations, directory.resolve("retained-original-generations"));
            Files.writeString(generations, "structurally damaged local cache");
            var lookups = new AtomicInteger();
            var observed = new PluginCheckpointTransactions() {
                public Scope scope() { return ledger.scope(); }
                public AcceptedState read() throws IOException { return ledger.read(); }
                public Optional<Decision> lookup(String id) throws IOException {
                    lookups.incrementAndGet();
                    return ledger.lookup(id);
                }
                public Decision submit(Operation operation) { throw new AssertionError("recovery must not submit"); }
            };
            var restarted = assertDoesNotThrow(() -> client(fixture, transport, observed));
            var recovered = restarted.recover("one");
            assertEquals(1, lookups.get());
            assertEquals(installed.decision(), recovered.decision());
            assertEquals(Outcome.COMMITTED, recovered.outcome());
            assertEquals("MISSING_OR_INVALID", recovered.localEvidenceStatus());
            assertNull(recovered.installation());
            assertEquals(Outcome.OUTCOME_UNKNOWN, restarted.recover("absent").outcome());
            fixture.responses.clear();
            assertEquals(recovered.decision(), restarted.install("one", publication.sources(), request()).decision());
            assertThrows(SecurityException.class, restarted::active);
            assertThrows(SecurityException.class, () -> restarted.remove("new-removal"));
        }
    }

    @Test void acknowledgedCommitDoesNotReportPreparedEvidenceAsStillAvailableAfterCacheDamage() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var publication = publish(fixture, "1.0.0", jar("one"), 1, "", fixture.publishers);
            var ledger = new PluginDistributionTransactionsTest.Ledger(scope(fixture));
            var damagedBeforeAnswer = new PluginCheckpointTransactions() {
                public Scope scope() { return ledger.scope(); }
                public AcceptedState read() throws IOException { return ledger.read(); }
                public Optional<Decision> lookup(String id) throws IOException { return ledger.lookup(id); }
                public Decision submit(Operation operation) throws IOException {
                    Decision committed = ledger.submit(operation);
                    Files.writeString(directory.resolve("packages/generations/")
                        .resolve(operation.update().installationHash().substring(7)).resolve("installation.json"), "{}");
                    return committed;
                }
            };
            var result = client(fixture, transport, damagedBeforeAnswer).install("one", publication.sources(), request());
            assertEquals(Outcome.COMMITTED, result.outcome());
            assertEquals(ledger.lookup("one").orElseThrow(), result.decision());
            assertEquals("MISSING_OR_INVALID", result.localEvidenceStatus());
            assertNull(result.installation());
        }
    }

    @Test void aDistinctAdmittedUriCannotReuseTheCommittedIntentThroughUtf8Replacement() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var publication = publish(fixture, "1.0.0", jar("one"), 1, "", fixture.publishers);
            var source = publication.sources();
            var ordinary = withTrustStoreUri(source, URI.create(source.trustStoreUri() + "?"));
            var malformed = withTrustStoreUri(source, URI.create(source.trustStoreUri().toString() + (char) 0xd800));
            assertNotEquals(ordinary, malformed);
            var ledger = new PluginDistributionTransactionsTest.Ledger(scope(fixture));
            var client = client(fixture, transport, ledger);
            assertEquals(Outcome.COMMITTED, client.install("one", ordinary, request()).outcome());
            fixture.responses.clear();
            assertThrows(SecurityException.class, () -> client.install("one", malformed, request()));
        }
    }

    @Test void validUnicodeUriKeepsTheExistingIntentHashAndReplaysWithoutDownloading() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var publication = publish(fixture, "1.0.0", jar("one"), 1, "", fixture.publishers);
            var sources = withTrustStoreUri(publication.sources(), URI.create(publication.sources().trustStoreUri() + "?q=λ🔒"));
            var scope = scope(fixture);
            var request = request();
            String originalHash = PluginDistributionJson.hash(PluginDistributionJson.canonical(Map.of(
                "schema", "regelsuche.plugin-distribution-intent/v1", "scope", scope, "action", "INSTALL",
                "inputs", Map.of("sources", sources, "request", request))));
            var client = client(fixture, transport, new PluginDistributionTransactionsTest.Ledger(scope));
            var installed = client.install("unicode", sources, request);
            assertEquals(Outcome.COMMITTED, installed.outcome());
            assertEquals(originalHash, installed.decision().operation().intentHash());
            fixture.responses.clear();
            assertEquals(installed.decision(), client.install("unicode", sources, request).decision());
        }
    }

    private static PluginDistributionClient.Sources withTrustStoreUri(PluginDistributionClient.Sources source, URI uri) {
        return new PluginDistributionClient.Sources(uri, source.trustRevisionUri(), source.indexUri(), source.indexSignatureUri(),
            source.indexId(), source.indexRevision(), source.indexContentHash());
    }

    private PluginDistributionTransactions client(PluginDistributionFixtures fixture,
            PluginDistributionTransport transport, PluginCheckpointTransactions authority) throws IOException {
        return new PluginDistributionTransactions(directory.resolve("packages"), fixture.roots, authority, transport,
            new PluginDistributionClient.Limits(65536, 65536, 1048576, 16, 64));
    }
    private static Scope scope(PluginDistributionFixtures fixture) {
        return new Scope("operator-slot", "community", PluginDistributionJson.hash(fixture.roots.toCanonicalJson()));
    }
    private static PluginDistributionTransport transport(PluginDistributionFixtures fixture) {
        return new PluginDistributionTransport(Set.of(fixture.origin), Duration.ofSeconds(2), Duration.ofSeconds(3), false, fixture.tls);
    }
    private static PluginArtifactResolver.ResolutionRequest request() {
        return PluginArtifactResolver.ResolutionRequest.exact("installation", PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN,
            "example", "1.0.0", "0.5.0", "1", List.of("algebra"));
    }
}
