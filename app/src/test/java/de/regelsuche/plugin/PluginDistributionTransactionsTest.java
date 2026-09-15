package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.plugin.PluginDistributionClientTest.publish;
import static de.regelsuche.plugin.PluginDistributionClientTest.jar;
import de.regelsuche.plugin.PluginCheckpointAuthority.AcceptedState;
import de.regelsuche.plugin.PluginCheckpointTransactions.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginDistributionTransactionsTest {
    @TempDir Path directory;

    @Test void lostCommitAnswerIsUnknownAndRecoveryUsesTheOriginalReceiptAfterRestart() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var publication = publish(fixture, "1.0.0", jar("one"), 1, "", fixture.publishers);
            var authority = new Ledger(scope(fixture));
            authority.loseAnswer = true;
            var client = client(fixture, transport, authority);
            var first = client.install("install-one", publication.sources(), request("1.0.0"));
            assertEquals(Outcome.OUTCOME_UNKNOWN, first.outcome());
            assertEquals("install-one", first.operationId());
            authority.loseAnswer = false;
            var restarted = client(fixture, transport, authority);
            var recovered = restarted.recover("install-one");
            assertEquals(Outcome.COMMITTED, recovered.outcome());
            assertEquals("AVAILABLE", recovered.localEvidenceStatus());
            assertArrayEquals(jar("one"), restarted.readArtifact(
                recovered.installation().artifacts().getFirst().identityHash()));
            // This would fail if retry reran install and consumed the same trust revision again.
            fixture.responses.clear();
            var retry = restarted.install("install-one", publication.sources(), request("1.0.0"));
            assertEquals(recovered.decision(), retry.decision());
            assertEquals(1L, restarted.active().orElseThrow().checkpoint().sequence());
            var removed = restarted.remove("remove-one");
            assertEquals(Outcome.COMMITTED, removed.outcome());
            assertTrue(restarted.active().orElseThrow().artifacts().isEmpty());
            assertEquals(recovered.decision(), restarted.recover("install-one").decision());
        }
    }

    @Test void aDifferentIntentCannotReuseAnOperationAndUnknownLookupNeverMeansGenesis() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var p = publish(fixture, "1.0.0", jar("one"), 1, "", fixture.publishers);
            var authority = new Ledger(scope(fixture));
            var client = client(fixture, transport, authority);
            assertEquals(Outcome.COMMITTED, client.install("one", p.sources(), request("1.0.0")).outcome());
            assertThrows(SecurityException.class, () -> client.remove("one"));
            assertThrows(SecurityException.class, () -> client.install("one", p.sources(), request("2.0.0")));
            assertEquals(Outcome.OUTCOME_UNKNOWN, client.recover("never-submitted").outcome());
            authority.unavailable = true;
            assertEquals(Outcome.OUTCOME_UNKNOWN, client.recover("one").outcome());
            assertThrows(IOException.class, client::active);
        }
    }

    @Test void corruptLocalGenerationDoesNotTurnAnEstablishedCommitIntoRejection() throws Exception {
        try (var fixture = new PluginDistributionFixtures(); var transport = transport(fixture)) {
            var p = publish(fixture, "1.0.0", jar("one"), 1, "", fixture.publishers);
            var authority = new Ledger(scope(fixture));
            var client = client(fixture, transport, authority);
            var installed = client.install("one", p.sources(), request("1.0.0"));
            assertEquals(Outcome.COMMITTED, installed.outcome());
            var file = directory.resolve("packages/generations/"
                + installed.installation().contentHash().substring(7) + "/installation.json");
            java.nio.file.Files.writeString(file, "{}");
            var recovered = client.recover("one");
            assertEquals(Outcome.COMMITTED, recovered.outcome());
            assertEquals("MISSING_OR_INVALID", recovered.localEvidenceStatus());
            assertNull(recovered.installation());
            assertThrows(SecurityException.class, client::active);
        }
    }

    @Test void additiveLifecycleKeepsEveryOriginalGenerationByteAndTrustAcrossUpdateAndRollback() throws Exception {
        try (var f = new PluginDistributionFixtures(); var transport = transport(f)) {
            var one = publish(f, "1.0.0", jar("one"), 1, "", f.publishers);
            var limits = new PluginDistributionClient.Limits(65536,65536,1048576,16,64);
            var legacy = new PluginDistributionClient(directory.resolve("legacy"), "community", f.roots,
                new PluginDistributionClientTest.TestAuthority(), transport, limits);
            var original = legacy.install(one.sources(), request("1.0.0"));
            var client = client(f, transport, new Ledger(scope(f)));
            var installed = client.install("install", one.sources(), request("1.0.0"));
            assertEquals(original.toCanonicalJson(), installed.installation().toCanonicalJson());
            Path left = directory.resolve("legacy/generations/" + original.contentHash().substring(7));
            Path right = directory.resolve("packages/generations/" + original.contentHash().substring(7));
            try (var files = java.nio.file.Files.walk(left)) {
                for (Path file : files.filter(java.nio.file.Files::isRegularFile).toList()) {
                    assertArrayEquals(java.nio.file.Files.readAllBytes(file),
                        java.nio.file.Files.readAllBytes(right.resolve(left.relativize(file))), file.toString());
                }
            }
            var two = publish(f, "2.0.0", jar("two"), 2, one.revision().contentHash(), f.publishers);
            var updated = client.install("update", two.sources(), request("2.0.0"));
            assertEquals(Outcome.COMMITTED, updated.outcome());
            assertEquals("UPDATE", updated.installation().operation());
            var back = client.rollback("rollback", original.contentHash());
            assertEquals(Outcome.COMMITTED, back.outcome());
            assertEquals(2L, back.installation().checkpoint().sequence());
            assertArrayEquals(jar("one"), client.readArtifact(back.installation().artifacts().getFirst().identityHash()));
        }
    }

    @Test void transactionRejectsForeignDomainRegressingOrChangedSameSequenceTrust() {
        var scope = new Scope("slot", "community", hash("roots"));
        var cp = checkpoint("community", 2, hash("r2"));
        var before = new AcceptedState(hash("old"), cp);
        for (var bad : List.of(
                checkpoint("other", 3, hash("r3")),
                checkpoint("community", 1, hash("r1")),
                checkpoint("community", 2, hash("fork")),
                checkpoint("community", 4, hash("r4")))) {
            assertThrows(IllegalArgumentException.class, () ->
                new Operation(scope, "op", hash("intent"), before, new AcceptedState(hash("new"), bad)));
        }
        var valid = new Operation(scope, "op", hash("intent"), before, new AcceptedState(hash("new"), cp));
        assertEquals(valid, Operation.read(valid.canonicalBytes()));
        assertThrows(IllegalArgumentException.class, () -> Operation.read(
            new String(valid.canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"sequence\":2", "\"sequence\":\"2\"").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private PluginDistributionTransactions client(PluginDistributionFixtures fixture,
            PluginDistributionTransport transport, Ledger authority) throws IOException {
        return new PluginDistributionTransactions(directory.resolve("packages"), fixture.roots, authority, transport,
            new PluginDistributionClient.Limits(65536, 65536, 1024 * 1024, 16, 64));
    }
    private static Scope scope(PluginDistributionFixtures f) {
        return new Scope("operator-slot", "community", PluginDistributionJson.hash(f.roots.toCanonicalJson()));
    }
    private static PluginTrustStoreRevisionVerifier.ChainCheckpoint checkpoint(String domain, long sequence, String revision) {
        String payload = "{\"schema\":\"regelsuche.plugin-trust-store-chain-checkpoint/v1\",\"trustDomainId\":\""
            + domain + "\",\"sequence\":" + sequence + ",\"revisionHash\":\"" + revision + "\"}\n";
        return new PluginTrustStoreRevisionVerifier.ChainCheckpoint(
            "regelsuche.plugin-trust-store-chain-checkpoint/v1", domain, sequence, revision, hash(payload));
    }
    private static String hash(String value) { return PluginDistributionJson.hash(value); }
    private static PluginDistributionTransport transport(PluginDistributionFixtures f) {
        return new PluginDistributionTransport(Set.of(f.origin), Duration.ofSeconds(2), Duration.ofSeconds(3), false, f.tls);
    }
    private static PluginArtifactResolver.ResolutionRequest request(String version) {
        return PluginArtifactResolver.ResolutionRequest.exact("installation", PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN,
            "example", version, "0.5.0", "1", List.of("algebra"));
    }

    /** Models an unavailable external ledger only; actual SQL/role/transaction tests are separate. */
    static final class Ledger implements PluginCheckpointTransactions {
        final Scope scope;
        final Map<String, Decision> decisions = new HashMap<>();
        AcceptedState state = AcceptedState.empty();
        boolean loseAnswer, unavailable;
        Ledger(Scope scope) { this.scope = scope; }
        public Scope scope() { return scope; }
        public synchronized AcceptedState read() throws IOException {
            if (unavailable) throw new IOException("unavailable");
            return state;
        }
        public synchronized Optional<Decision> lookup(String id) throws IOException {
            if (unavailable) throw new IOException("unavailable");
            return Optional.ofNullable(decisions.get(id));
        }
        public synchronized Decision submit(Operation op) throws IOException {
            var prior = decisions.get(op.operationId());
            if (prior != null) {
                if (!prior.operation().equals(op)) throw new SecurityException("operation conflict");
                return prior;
            }
            boolean committed = state.equals(op.expected());
            if (committed) state = op.update();
            var decision = new Decision(op, committed ? Outcome.COMMITTED : Outcome.REJECTED);
            decisions.put(op.operationId(), decision);
            return loseAnswer ? new Decision(op, Outcome.OUTCOME_UNKNOWN) : decision;
        }
    }
}
