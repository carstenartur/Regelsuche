package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.plugin.PluginDistributionClientTest.publish;
import de.regelsuche.plugin.PluginCheckpointTransactions.*;
import de.regelsuche.plugin.PluginCheckpointAuthority.AcceptedState;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstalledDiscoveryCommandTest {
    @TempDir Path directory;
    @Test void signedOfflineSearchExportsFrozenOriginAndLateResource() throws Exception {
        try (var f = new Fixture()) {
            var installed = f.install("1.0.0", "normal");
            assertThrows(ClassNotFoundException.class, () -> Class.forName("installeddiscovery.Provider"));
            f.offline();
            assertEquals(0, f.execute("list"), f.output::toString);
            assertTrue(f.output.toString().contains("installation=" + installed.contentHash()));
            assertTrue(f.output.toString().contains("contribution=search"));
            assertEquals(0, f.run("confirmed.json", "small"), f.output::toString);
            String evidence = Files.readString(directory.resolve("confirmed.json"));
            assertTrue(evidence.contains("CONFIRMED"));
            assertTrue(evidence.contains("late-1.0.0"));
            var properties = properties(evidence);
            assertEquals(installed.contentHash(),properties.get("sdk.provider.extension.trustEvidenceSha256"));
            assertEquals(installed.artifacts().getFirst().artifactSha256(), properties.get("sdk.provider.artifactSha256"));
            assertEquals("search",properties.get("sdk.provider.extension.contributionId"));
            assertTrue(properties.get("sdk.provider.extension.catalogSha256").matches("sha256:[a-f0-9]{64}"));
            assertTrue(evidence.contains("sdk.provider.authority"));
            assertTrue(evidence.contains("NOT_EVALUATED"));
            assertEquals(2, f.closed);
            f.assertRuntimeClosed();
        }
    }
    @Test void everyListedIdentifierRevisionCanBeSelectedForInstalledRun() throws Exception {
        for (String revision : List.of("release/1", "release:1", "r".repeat(65))) {
            try (var f = new Fixture()) {
                f.install("1.0.0", "normal", revision);
                f.offline();
                assertEquals(0, f.execute("list"), f.output::toString);
                assertTrue(f.output.toString().contains("installed-search@" + revision));
                String result = "revision-" + revision.hashCode() + ".json";
                assertEquals(0, f.execute("run", "installed-provider", "installed-search@" + revision,
                    "campaign", directory.resolve("seed.txt").toString(), directory.resolve(result).toString()),
                    f.output::toString);
                var evidence = new com.fasterxml.jackson.databind.ObjectMapper().readTree(directory.resolve(result).toFile());
                assertEquals(revision, evidence.path("domain").path("revision").asText());
            }
        }
    }
    @Test void tinyCollisionAndInvalidInputKeepCodesAndReleaseResources() throws Exception {
        try (var f = new Fixture()) {
            var installed = f.install("1.0.0", "normal"); f.offline();
            assertEquals(3, f.run("tiny.json", "tiny"), f.output::toString);
            assertTrue(Files.readString(directory.resolve("tiny.json")).contains(installed.contentHash()));
            String original = Files.readString(directory.resolve("tiny.json"));
            assertEquals(1, f.run("tiny.json", "small"));
            assertEquals(original, Files.readString(directory.resolve("tiny.json")));
            assertEquals(1, f.execute("run","wrong-provider","installed-search@v1","campaign",
                directory.resolve("seed.txt").toString(), directory.resolve("wrong.json").toString()));
            for (byte[] seed : List.of(new byte[]{(byte)255}, new byte[1048577])) {
                Files.write(directory.resolve("seed.txt"),seed);
                assertEquals(1, f.run("invalid.json","small"));
                assertFalse(Files.exists(directory.resolve("invalid.json")));
            }
            assertEquals(5,f.closed);
        }
    }
    @Test void reopeningSeesUpdateRemovalAndUnavailableAuthority() throws Exception {
        try (var f = new Fixture()) {
            assertEquals(0,f.execute("list"));
            assertTrue(f.output.toString().contains("NONE (pre-genesis)"));
            f.install("1.0.0","normal");
            assertEquals(0,f.run("one.json","small"));
            var updated = f.install("2.0.0","normal");
            assertEquals(0,f.run("two.json","small"));
            assertTrue(Files.readString(directory.resolve("two.json")).contains("late-2.0.0"));
            assertTrue(Files.readString(directory.resolve("two.json")).contains(updated.contentHash()));
            var removed = f.transactions.remove("remove").installation();
            f.offline();
            assertEquals(0,f.execute("list"));
            assertTrue(f.output.toString().contains(removed.contentHash()));
            assertFalse(f.output.toString().contains("NONE"));
            assertEquals(1,f.run("removed.json","small"));
            f.ledger.delegate.unavailable = true;
            assertEquals(1,f.run("unavailable.json","small"));
            assertTrue(f.output.toString().contains("ACTIVATION (IOException)"));
            assertFalse(Files.exists(directory.resolve("unavailable.json")));
            assertFalse(Files.exists(directory.resolve("removed.json")));
        }
    }
    @Test void tamperedSelectedJarRejectsBeforeInitialization() throws Exception {
        try (var f = new Fixture()) {
            var installed=f.install("1.0.0","normal"); f.offline();
            var artifact=installed.artifacts().getFirst();
            Files.write(directory.resolve("packages/generations/"+installed.contentHash().substring(7)).resolve(artifact.path()),new byte[]{1,2,3});
            assertEquals(1,f.run("tampered.json","small"));
            assertNull(System.getProperty(f.marker));
            assertFalse(Files.exists(directory.resolve("tampered.json")));
            assertEquals(1,f.closed);
        }
    }
    @Test void providerFailureIsSanitizedAndClosesSession() throws Exception {
        for (String failure : List.of("domains-failure","evidence-failure","forbidden")) {
            // Each test invocation has its own authority/cache and external classloader.
            try (var f = new Fixture()) {
                f.install("1.0.0",failure); f.offline();
                assertEquals(1,f.run(failure+".json","small"),f.output::toString);
                assertTrue(f.output.toString().startsWith("Installed discovery failed:"));
                assertTrue(f.output.size()<200);
                assertEquals(1,f.closed);
                assertFalse(Files.exists(directory.resolve(failure+".json")));
            }
        }
    }
    @Test void admittedDependencySeparatesContributorAndObservedProviderHashes() throws Exception {
        try (var f = new Fixture()) {
            var closure = InstalledDiscoveryFixture.closure(directory.resolve("closure"), f.marker);
            var dep = f.entry("dependency",closure.provider(),List.of());
            var root = f.entry("example",closure.contributor(),List.of(new PluginArtifactIndex.Dependency(
                PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN,"dependency","=1.0.0",false)));
            // Reuse real artifact/provenance signing, then sign the composed two-artifact index.
            var publication = publish(f.distribution,dep,closure.provider(),1,"",f.distribution.publishers);
            var artifactSignature = PluginSignatureManifest.create(root.artifactFileName(),root.artifactSha256(),"publisher","key-1",
                PluginDistributionFixtures.sign(f.distribution.publisherKey,PluginSignatureManifest.signedPayload(
                    root.artifactFileName(),root.artifactSha256(),"publisher","key-1","Ed25519")));
            f.distribution.responses.put("/example.jar",closure.contributor());
            f.distribution.responses.put("/example.jar.sig.json",artifactSignature.toCanonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            f.distribution.responses.put("/example.jar.provenance.json",PluginArtifactProvenanceTest.signed(f.distribution,root)
                .toCanonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var index = PluginArtifactIndex.create("community-index","revision-1","curator",List.of(root,dep));
            var signature = PluginArtifactIndexSignature.create(index.indexId(),index.revision(),index.contentHash(),
                "curator","key-1",PluginDistributionFixtures.sign(f.distribution.curatorKey,
                    PluginArtifactIndexSignature.signedPayload(index.indexId(),index.revision(),index.contentHash(),"curator","key-1","Ed25519")));
            f.distribution.responses.put("/index.json",index.toCanonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            f.distribution.responses.put("/index.sig.json",signature.toCanonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var old=publication.sources();
            var sources=new PluginDistributionClient.Sources(old.trustStoreUri(),old.trustRevisionUri(),old.indexUri(),
                old.indexSignatureUri(),index.indexId(),index.revision(),index.contentHash());
            var result=f.transactions.install("closure",sources,InstalledExtensionRuntimeTest.request("1.0.0","1.0.0","2"));
            assertEquals(Outcome.COMMITTED,result.outcome());
            assertEquals(2,result.installation().artifacts().size()); f.offline();
            assertEquals(0,f.run("closure.json","small"),f.output::toString);
            String evidence=Files.readString(directory.resolve("closure.json"));
            assertNotEquals(root.artifactSha256(),dep.artifactSha256());
            assertTrue(evidence.contains("\"key\":\"sdk.provider.artifactSha256\",\"value\":\""+dep.artifactSha256()+"\""));
            assertTrue(evidence.contains("\"key\":\"sdk.provider.extension.artifactSha256\",\"value\":\""+root.artifactSha256()+"\""));
            assertTrue(evidence.contains(result.installation().contentHash()));
        }
    }
    @Test void invalidPrefixOptionsSelectorsAndBudgetsFailBeforeSessionConstruction() {
        for (String[] args : List.of(new String[]{},new String[]{"list"},
                new String[]{"--config","relative","list","extra"},
                new String[]{"--config","relative","list","--config","again"},
                new String[]{"--config","relative","run","provider","domain","campaign","seed","out"},
                new String[]{"--config","relative","run","provider","domain@v1","campaign","seed","out","unbounded"})) {
            var output = new ByteArrayOutputStream();
            assertEquals(1,InstalledDiscoveryCommand.run(args,new PrintStream(output),Map.of(),
                (config,env) -> { throw new AssertionError("invalid request opened session"); }));
            assertTrue(output.toString().contains("REQUEST (IllegalArgumentException)"));
        }
    }
    @Test void publicConfigurationFailureSuppressesPathsAndSecrets() throws Exception {
        Path config=Files.writeString(directory.resolve("secret-password.json"),"{\"jdbc:secret-password\":true}");
        var output=new ByteArrayOutputStream();
        assertEquals(1,InstalledDiscoveryCommand.run(new String[]{"--config",config.toString(),"list"},new PrintStream(output),Map.of("PASSWORD","secret-password")));
        assertTrue(output.toString().contains("CONFIGURATION"));
        assertFalse(output.toString().contains("secret-password"));
        assertFalse(output.toString().contains("jdbc:"));
    }
    private static Map<String,String> properties(String evidence) throws IOException {
        var result = new HashMap<String,String>();
        for (var property : new com.fasterxml.jackson.databind.ObjectMapper().readTree(evidence).path("domainEvidence").path("properties"))
            result.put(property.path("key").asText(),property.path("value").asText());
        return result;
    }
    final class Fixture implements AutoCloseable {
        final PluginDistributionFixtures distribution = new PluginDistributionFixtures();
        final PluginDistributionTransport transport = new PluginDistributionTransport(Set.of(distribution.origin),
            Duration.ofSeconds(2), Duration.ofSeconds(3), false, distribution.tls);
        final Scope scope = new Scope("slot", "community", PluginDistributionJson.hash(distribution.roots.toCanonicalJson()));
        final CountingLedger ledger = new CountingLedger(scope);
        final PluginDistributionTransactions transactions = new PluginDistributionTransactions(directory.resolve("packages"),
            distribution.roots, ledger, transport, new PluginDistributionClient.Limits(65536,65536,1048576,16,64));
        final String marker = "installed.discovery." + UUID.randomUUID();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final Path config;
        int closed, sequence;
        String previous = "";
        Fixture() throws Exception {
            var value = new PluginPackageCommand.Config("regelsuche.plugin-package-config/v1",
                directory.resolve("packages").toString(), "slot", "community", distribution.roots,
                new PluginPackageCommand.Authority("jdbc:postgresql://127.0.0.1:1/unused", "client", "PASSWORD",
                    directory.resolve("root.crt").toString(), 1), Set.of(distribution.origin),1,1,
                new PluginDistributionClient.Limits(65536,65536,1048576,16,64),null,null);
            config = directory.resolve("config.json");
            new com.fasterxml.jackson.databind.ObjectMapper().writeValue(config.toFile(),value);
            Files.writeString(directory.resolve("seed.txt"), "state=0");
        }
        PluginInstallationEvidence install(String version, String behavior) throws Exception {
            return install(version, behavior, "v1");
        }
        PluginInstallationEvidence install(String version, String behavior, String revision) throws Exception {
            byte[] jar = InstalledDiscoveryFixture.jar(directory.resolve("external-" + (++sequence)), marker, version, behavior, revision);
            var publication = publish(distribution, distribution.entry(version,jar,"2","1.0.0",""),jar,sequence,previous,distribution.publishers);
            previous = publication.revision().contentHash();
            var result = transactions.install("install-"+sequence,publication.sources(),InstalledExtensionRuntimeTest.request(version,"1.0.0","2"));
            assertEquals(Outcome.COMMITTED,result.outcome());
            return result.installation();
        }
        PluginArtifactIndex.Entry entry(String id,byte[] bytes,List<PluginArtifactIndex.Dependency> dependencies) {
            String file=id+".jar";
            return PluginArtifactIndex.Entry.create(id,PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN,id,"1.0.0","2","1.0.0","",
                List.of(),dependencies,file,PluginArtifactVerifier.sha256(bytes),distribution.origin.resolve("/"+file).toString(),
                distribution.origin.resolve("/"+file+".sig.json").toString(),distribution.origin.resolve("/"+file+".provenance.json").toString(),"publisher");
        }
        void offline() { distribution.responses.clear(); transport.close(); }
        int execute(String... args) {
            output.reset(); int before = ledger.reads;
            var full = new ArrayList<>(List.of("--config",config.toString())); full.addAll(List.of(args));
            int code = InstalledDiscoveryCommand.run(full.toArray(String[]::new), new PrintStream(output),Map.of(),
                (configuration, environment) -> new PluginPackageSession(scope,transactions,() -> { assertRuntimeClosed(); closed++; }));
            assertEquals(before+1,ledger.reads);
            assertFalse(output.toString().contains("secret-password"));
            assertFalse(output.toString().contains("jdbc:"));
            return code;
        }
        int run(String result,String budget) { return execute("run","installed-provider","installed-search@v1","campaign",
            directory.resolve("seed.txt").toString(),directory.resolve(result).toString(),budget); }
        void assertRuntimeClosed() {
            String stage = System.getProperty(marker+".stage");
            if (stage != null) assertFalse(Files.exists(Path.of(URI.create(stage))), "provider staging must close before session");
            String resource = System.getProperty(marker+".resource");
            if (resource != null) {
                String file = resource.substring(4,resource.indexOf("!/"));
                assertFalse(Files.exists(Path.of(URI.create(file))), "staged JAR must close before session");
            }
        }
        public void close() { transport.close(); distribution.close(); System.clearProperty(marker); System.clearProperty(marker+".resource"); System.clearProperty(marker+".stage"); }
    }
    static final class CountingLedger implements PluginCheckpointTransactions {
        final PluginDistributionTransactionsTest.Ledger delegate;
        int reads;
        CountingLedger(Scope scope) { delegate = new PluginDistributionTransactionsTest.Ledger(scope); }
        public Scope scope() { return delegate.scope(); }
        public AcceptedState read() throws IOException { reads++; return delegate.read(); }
        public Optional<Decision> lookup(String id) throws IOException { return delegate.lookup(id); }
        public Decision submit(Operation operation) throws IOException { return delegate.submit(operation); }
    }
}
