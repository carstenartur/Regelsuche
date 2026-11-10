package de.regelsuche.plugin;

import static de.regelsuche.plugin.PluginDistributionClientTest.publish;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.extension.ExtensionPoint;
import de.regelsuche.plugin.PluginCheckpointAuthority.AcceptedState;
import de.regelsuche.plugin.PluginCheckpointTransactions.Outcome;
import de.regelsuche.plugin.PluginCheckpointTransactions.Scope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstalledExtensionRuntimeTest {
    private static final ExtensionPoint<Runnable> RUN = ExtensionPoint.of("installed.run", Runnable.class);
    private static final PluginDistributionClient.Limits LIMITS =
        new PluginDistributionClient.Limits(65536, 65536, 1048576, 16, 64);
    @TempDir Path directory;
    final String marker = "regelsuche.installed-test." + UUID.randomUUID();

    @AfterEach void clearMarkers() {
        System.clearProperty(marker);
        System.clearProperty(marker + ".value");
    }

    @Test void activatesRealSignedV2OfflineWithInstallationBoundOrigin() throws Exception {
        try (var f = new PluginDistributionFixtures(); var transport = transport(f)) {
            byte[] jar = jar("one");
            var publication = publish(f, f.entry("1.0.0", jar, "2", "1.0.0", ""),
                jar, 1, "", f.publishers);
            var authority = new CountingAuthority();
            var client = client(f, transport, authority);
            var installed = client.install(publication.sources(), request("1.0.0", "1.0.0", "2"));
            assertNull(System.getProperty(marker));
            assertThrows(ClassNotFoundException.class, () -> Class.forName("installed.RealProvider"));
            f.responses.clear();
            transport.close();
            int before = authority.reads;
            var runtime = InstalledExtensionRuntime.open(client);
            try {
                assertEquals(before + 1, authority.reads);
                var contribution = runtime.catalog().find(RUN, "run").orElseThrow();
                contribution.implementation().run();
                assertEquals("one", System.getProperty(marker + ".value"));
                assertEquals(installed, runtime.installation().orElseThrow());
                assertEquals(installed.artifacts().getFirst().artifactSha256(), contribution.origin().artifactSha256());
                assertEquals(installed.contentHash(), contribution.origin().trustEvidenceSha256());
                assertTrue(contribution.origin().sourceReference().contains(installed.contentHash()));
                assertNotSame(getClass().getClassLoader(), contribution.implementation().getClass().getClassLoader());
                try (var again = InstalledExtensionRuntime.open(client)) {
                    assertEquals(runtime.catalog().contentHash(), again.catalog().contentHash());
                }
            } finally { runtime.close(); }
            runtime.close();
            assertThrows(IllegalStateException.class, runtime::catalog);
        }
    }

    @Test void closureReadUsesOneAuthoritySelectionAndReturnsDefensiveBytes() throws Exception {
        try (var f = new PluginDistributionFixtures(); var transport = transport(f)) {
            byte[] jar = jar("one");
            var p = publish(f, f.entry("1.0.0", jar, "2", "1.0.0", ""), jar, 1, "", f.publishers);
            var authority = new CountingAuthority();
            var client = client(f, transport, authority);
            var installed = client.install(p.sources(), request("1.0.0", "1.0.0", "2"));
            authority.rejectSecondRead = true;
            authority.reads = 0;
            var snapshot = client.installedSnapshot().orElseThrow();
            assertEquals(1, authority.reads);
            byte[] returned = snapshot.artifacts().getFirst().bytes();
            returned[0] ^= 1;
            assertArrayEquals(jar, snapshot.artifacts().getFirst().bytes());
            Path retained = generation(installed).resolve(installed.artifacts().getFirst().path());
            Files.write(retained, returned);
            assertArrayEquals(jar, snapshot.artifacts().getFirst().bytes());
            authority.rejectSecondRead = false;
            assertThrows(SecurityException.class, () -> InstalledExtensionRuntime.open(client));
            assertNull(System.getProperty(marker), "corrupt selected bytes must fail before initialization");
        }
    }

    @Test void reopeningObservesUpdateRemovalAndRollbackWithoutRollingBackTrust() throws Exception {
        try (var f = new PluginDistributionFixtures(); var transport = transport(f)) {
            var authority = new CountingAuthority();
            var client = client(f, transport, authority);
            try (var empty = InstalledExtensionRuntime.open(client)) {
                assertTrue(empty.installation().isEmpty());
                assertTrue(empty.catalog().registrations(RUN).isEmpty());
            }
            byte[] first = jar("one");
            var p1 = publish(f, f.entry("1.0.0", first, "2", "1.0.0", ""), first, 1, "", f.publishers);
            var installed = client.install(p1.sources(), request("1.0.0", "1.0.0", "2"));
            assertValue(client, "one");
            byte[] second = jar("two");
            var p2 = publish(f, f.entry("2.0.0", second, "2", "1.0.0", ""), second, 2,
                p1.revision().contentHash(), f.publishers);
            client.install(p2.sources(), request("2.0.0", "1.0.0", "2"));
            assertValue(client, "two");
            var removed = client.remove();
            try (var empty = InstalledExtensionRuntime.open(client)) {
                assertEquals(removed, empty.installation().orElseThrow());
                assertTrue(empty.catalog().registrations(RUN).isEmpty());
            }
            var rollback = client.rollback(installed.contentHash());
            assertEquals(2, rollback.checkpoint().sequence());
            assertValue(client, "one");
            authority.failRead = true;
            assertThrows(IOException.class, () -> InstalledExtensionRuntime.open(client));
        }
    }

    @Test void incompatibleSignedApiAndHostBoundsRejectBeforeInitialization() throws Exception {
        for (boolean oldApi : new boolean[] {true, false}) {
            try (var f = new PluginDistributionFixtures(); var transport = transport(f)) {
                byte[] bytes = jar("incompatible");
                String api = oldApi ? "1" : "2";
                String core = oldApi ? "1.0.0" : "0.5.0";
                var p = publish(f, f.entry("1.0.0", bytes, api, "0.5.0", oldApi ? "" : "0.9.0"),
                    bytes, 1, "", f.publishers);
                var client = new PluginDistributionClient(directory.resolve("incompatible-" + oldApi),
                    "community", f.roots, new CountingAuthority(), transport, LIMITS);
                client.install(p.sources(), request("1.0.0", core, api));
                assertThrows(SecurityException.class, () -> InstalledExtensionRuntime.open(client));
                assertNull(System.getProperty(marker));
            }
        }
    }

    @Test void transactionActivationUsesCurrentStateAfterLostAnswerRecoveryAndRestart() throws Exception {
        try (var f = new PluginDistributionFixtures(); var transport = transport(f)) {
            var scope = new Scope("operator-slot", "community", PluginDistributionJson.hash(f.roots.toCanonicalJson()));
            var authority = new PluginDistributionTransactionsTest.Ledger(scope);
            var transactions = new PluginDistributionTransactions(directory.resolve("packages"), f.roots,
                authority, transport, LIMITS);
            byte[] first = jar("one");
            var p1 = publish(f, f.entry("1.0.0", first, "2", "1.0.0", ""), first, 1, "", f.publishers);
            authority.loseAnswer = true;
            assertEquals(Outcome.OUTCOME_UNKNOWN,
                transactions.install("one", p1.sources(), request("1.0.0", "1.0.0", "2")).outcome());
            authority.loseAnswer = false;
            var recovered = transactions.recover("one");
            assertEquals(Outcome.COMMITTED, recovered.outcome());

            byte[] second = jar("two");
            var p2 = publish(f, f.entry("2.0.0", second, "2", "1.0.0", ""), second, 2,
                p1.revision().contentHash(), f.publishers);
            var updated = transactions.install("two", p2.sources(), request("2.0.0", "1.0.0", "2"));
            assertEquals(Outcome.COMMITTED, updated.outcome());
            var restarted = new PluginDistributionTransactions(directory.resolve("packages"), f.roots,
                authority, transport, LIMITS);
            f.responses.clear();
            transport.close();
            assertEquals(recovered.decision(), restarted.recover("one").decision());
            try (var runtime = InstalledExtensionRuntime.open(restarted)) {
                assertEquals(updated.installation(), runtime.installation().orElseThrow());
                runtime.catalog().find(RUN, "run").orElseThrow().implementation().run();
                assertEquals("two", System.getProperty(marker + ".value"));
            }
            assertEquals(Outcome.COMMITTED, restarted.remove("remove").outcome());
            assertEquals(recovered.decision(), restarted.recover("one").decision());
            try (var runtime = InstalledExtensionRuntime.open(restarted)) {
                assertTrue(runtime.catalog().registrations(RUN).isEmpty());
                assertEquals("REMOVE", runtime.installation().orElseThrow().operation());
            }
            authority.unavailable = true;
            assertThrows(IOException.class, () -> InstalledExtensionRuntime.open(restarted));
        }
    }

    @Test void authoritySwitchDuringReadCannotMixGenerationsAndAppliesToNextOpen() throws Exception {
        try (var f = new PluginDistributionFixtures(); var transport = transport(f)) {
            var authority = new CountingAuthority();
            var client = client(f, transport, authority);
            byte[] first = jar("one");
            var p1 = publish(f, f.entry("1.0.0", first, "2", "1.0.0", ""), first, 1, "", f.publishers);
            var installed = client.install(p1.sources(), request("1.0.0", "1.0.0", "2"));
            byte[] second = jar("two");
            var p2 = publish(f, f.entry("2.0.0", second, "2", "1.0.0", ""), second, 2,
                p1.revision().contentHash(), f.publishers);
            var staged = client.prepareInstall(p2.sources(), request("2.0.0", "1.0.0", "2"));
            authority.switchAfterRead = new AcceptedState(staged.evidence().contentHash(), staged.evidence().checkpoint());
            authority.reads = 0;
            try (var runtime = InstalledExtensionRuntime.open(client)) {
                assertEquals(1, authority.reads);
                assertEquals(installed, runtime.installation().orElseThrow());
                var oldContribution = runtime.catalog().find(RUN, "run").orElseThrow();
                oldContribution.implementation().run();
                assertEquals("one", System.getProperty(marker + ".value"));
                assertValue(client, "two");
                // Already-live contributions retain their immutable activation snapshot.
                oldContribution.implementation().run();
                assertEquals("one", System.getProperty(marker + ".value"));
            }
        }
    }

    @Test void retainedSignatureIsReverifiedEvenWhenEveryCacheHashIsConsistent() throws Exception {
        try (var f = new PluginDistributionFixtures(); var transport = transport(f)) {
            byte[] bytes = jar("one");
            var p = publish(f, f.entry("1.0.0", bytes, "2", "1.0.0", ""), bytes, 1, "", f.publishers);
            var authority = new CountingAuthority();
            var client = client(f, transport, authority);
            var installed = client.install(p.sources(), request("1.0.0", "1.0.0", "2"));
            var store = new PluginInstallationStore(directory.resolve("packages"), LIMITS);
            var files = new LinkedHashMap<>(store.load(installed.contentHash()).files());
            var signature = PluginArtifactIndexSignature.read(generation(installed).resolve("index.sig.json"));
            byte[] changed = Base64.getDecoder().decode(signature.signatureBase64());
            changed[0] ^= 1;
            var invalid = PluginArtifactIndexSignature.create(signature.indexId(), signature.revision(),
                signature.indexContentHash(), signature.curatorId(), signature.keyId(),
                Base64.getEncoder().encodeToString(changed));
            files.put("index.sig.json", invalid.toCanonicalJson().getBytes(StandardCharsets.UTF_8));
            var forged = PluginInstallationEvidence.create("UPDATE", installed.contentHash(), "",
                installed.checkpoint(), installed.rootTrustStoreHash(), installed.trustStoreHash(),
                installed.indexId(), installed.indexRevision(), installed.indexContentHash(),
                installed.resolutionContentHash(), installed.artifacts(), PluginInstallationStore.hashes(files));
            store.persist(forged, files);
            assertTrue(authority.compareAndSet(new AcceptedState(installed.contentHash(), installed.checkpoint()),
                new AcceptedState(forged.contentHash(), forged.checkpoint())));
            assertEquals(forged, client.active().orElseThrow(), "cache hashes alone must accept this fixture");
            assertThrows(SecurityException.class, () -> InstalledExtensionRuntime.open(client));
            assertNull(System.getProperty(marker));
        }
    }

    @Test void signedCompatibilityDoesNotBypassTheProviderDescriptorChecks() throws Exception {
        for (boolean oldApi : new boolean[] {true, false}) {
            try (var f = new PluginDistributionFixtures(); var transport = transport(f)) {
                System.clearProperty(marker);
                byte[] bytes = InstalledExtensionFixture.jar(directory.resolve("descriptor-" + oldApi), marker,
                    "bad-descriptor", oldApi ? "1" : "2", oldApi ? "1.0.0" : "2.0.0", false);
                var p = publish(f, f.entry("1.0.0", bytes, "2", "1.0.0", ""), bytes, 1, "", f.publishers);
                var client = new PluginDistributionClient(directory.resolve("descriptor-packages-" + oldApi),
                    "community", f.roots, new CountingAuthority(), transport, LIMITS);
                client.install(p.sources(), request("1.0.0", "1.0.0", "2"));
                assertThrows(RuntimeException.class, () -> InstalledExtensionRuntime.open(client));
                // Descriptor methods are executable plugin code, so this check necessarily follows initialization.
                assertEquals("initialized", System.getProperty(marker));
                assertNull(System.getProperty(marker + ".value"));
            }
        }
    }

    @Test void manifestClasspathStillRejectsBeforeProviderInitialization() throws Exception {
        try (var f = new PluginDistributionFixtures(); var transport = transport(f)) {
            byte[] bytes = InstalledExtensionFixture.jar(directory.resolve("manifest"), marker,
                "bad", "2", "1.0.0", true);
            var p = publish(f, f.entry("1.0.0", bytes, "2", "1.0.0", ""), bytes, 1, "", f.publishers);
            var client = client(f, transport, new CountingAuthority());
            client.install(p.sources(), request("1.0.0", "1.0.0", "2"));
            assertThrows(RuntimeException.class, () -> InstalledExtensionRuntime.open(client));
            assertNull(System.getProperty(marker));
        }
    }

    private byte[] jar(String value) throws Exception {
        return InstalledExtensionFixture.jar(directory.resolve("provider-" + value), marker, value, "2", "1.0.0", false);
    }

    private void assertValue(PluginDistributionClient client, String value) throws Exception {
        try (var runtime = InstalledExtensionRuntime.open(client)) {
            runtime.catalog().find(RUN, "run").orElseThrow().implementation().run();
            assertEquals(value, System.getProperty(marker + ".value"));
        }
    }

    private PluginDistributionClient client(PluginDistributionFixtures f,
            PluginDistributionTransport transport, PluginCheckpointAuthority authority) throws IOException {
        return new PluginDistributionClient(directory.resolve("packages"), "community", f.roots,
            authority, transport, LIMITS);
    }

    private Path generation(PluginInstallationEvidence evidence) {
        return directory.resolve("packages/generations/" + evidence.contentHash().substring(7));
    }

    private static PluginDistributionTransport transport(PluginDistributionFixtures f) {
        return new PluginDistributionTransport(Set.of(f.origin), Duration.ofSeconds(2), Duration.ofSeconds(3), false, f.tls);
    }

    static PluginArtifactResolver.ResolutionRequest request(String version, String core, String api) {
        return PluginArtifactResolver.ResolutionRequest.exact("activate", PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN,
            "example", version, core, api, java.util.List.of());
    }

    static final class CountingAuthority implements PluginCheckpointAuthority {
        final PluginDistributionClientTest.TestAuthority delegate = new PluginDistributionClientTest.TestAuthority();
        int reads;
        boolean rejectSecondRead;
        boolean failRead;
        AcceptedState switchAfterRead;
        public AcceptedState read() throws IOException {
            reads++;
            if (failRead || rejectSecondRead && reads > 1) throw new IOException("unexpected authority read");
            AcceptedState selected = delegate.read();
            if (switchAfterRead != null) {
                assertTrue(delegate.compareAndSet(selected, switchAfterRead));
                switchAfterRead = null;
            }
            return selected;
        }
        public boolean compareAndSet(AcceptedState expected, AcceptedState update) throws IOException {
            return delegate.compareAndSet(expected, update);
        }
    }
}
