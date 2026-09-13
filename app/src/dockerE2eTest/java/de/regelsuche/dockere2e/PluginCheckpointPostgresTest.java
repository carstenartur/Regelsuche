package de.regelsuche.dockere2e;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.plugin.*;
import de.regelsuche.plugin.PluginCheckpointTransactions.*;
import de.regelsuche.plugin.PluginCheckpointAuthority.AcceptedState;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real SQL, permissions and commit/recovery controls; no disabledWithoutDocker skip. */
@Testcontainers
class PluginCheckpointPostgresTest {
    @Container static final GenericContainer<?> POSTGRES = PinnedPostgresContainer.create();
    static final AtomicInteger IDS = new AtomicInteger();
    Scope scope;
    String role;
    PostgresPluginCheckpointAuthority authority;

    @BeforeAll static void schema() throws Exception {
        try (Connection db = admin(); var input = PluginCheckpointPostgresTest.class.getResourceAsStream(
                "/plugin-distribution/checkpoint-postgresql-v1.sql")) {
            assertNotNull(input);
            db.createStatement().execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @BeforeEach void provisionSeparateClientRole() throws Exception {
        role = "plugin_client_" + IDS.incrementAndGet();
        scope = new Scope(role, "community", hash("roots"));
        try (Connection db = admin(); Statement ddl = db.createStatement()) {
            ddl.execute("CREATE ROLE " + role + " LOGIN PASSWORD 'synthetic-test-only'");
            ddl.execute("GRANT USAGE ON SCHEMA plugin_checkpoints TO " + role);
            ddl.execute("GRANT EXECUTE ON FUNCTION plugin_checkpoints.read_state(text,text,text), "
                + "plugin_checkpoints.lookup_operation(text,text,text,text), plugin_checkpoints.submit_operation(text) TO " + role);
            try (var insert = db.prepareStatement("INSERT INTO plugin_checkpoints.slots"
                    + "(installation_id,trust_domain,root_hash,client_role,maximum_operations) VALUES(?,?,?,?,32)")) {
                insert.setString(1, scope.installationId()); insert.setString(2, scope.trustDomain());
                insert.setString(3, scope.rootTrustStoreHash()); insert.setString(4, role); insert.executeUpdate();
            }
        }
        authority = new PostgresPluginCheckpointAuthority(this::connect, scope, 3);
    }

    @Test void committedButLostAcknowledgementRecoversTheSameDurableDecisionAfterLaterCommits() throws Exception {
        var op = operation("one", AcceptedState.empty(), 1);
        AtomicBoolean lose = new AtomicBoolean(true);
        var disrupted = new PostgresPluginCheckpointAuthority(() -> {
            Connection real = connect();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    try {
                        Object value = method.invoke(real, args);
                        if (method.getName().equals("commit") && lose.getAndSet(false)) {
                            throw new SQLException("synthetic lost acknowledgement AFTER real PostgreSQL commit");
                        }
                        return value;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        }, scope, 3);
        assertEquals(Outcome.OUTCOME_UNKNOWN, disrupted.submit(op).outcome());
        assertEquals(op.update(), authority.read());
        var receipt = authority.lookup("one").orElseThrow();
        assertEquals(Outcome.COMMITTED, receipt.outcome());
        assertEquals(receipt, authority.submit(op));
        var second = operation("two", op.update(), 2);
        assertEquals(Outcome.COMMITTED, authority.submit(second).outcome());
        assertEquals(receipt, authority.lookup("one").orElseThrow());
        assertEquals(receipt, authority.submit(op), "a historical replay must not overwrite newer state");
        assertEquals(second.update(), authority.read());
        assertTrue(authority.lookup("absent").isEmpty());
    }

    @Test void independentConnectionsRaceOneWholePairAndRecordTheLosingDecision() throws Exception {
        var a = operation("a", AcceptedState.empty(), 1);
        var b = operation("b", AcceptedState.empty(), 1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var fa = executor.submit(() -> { start.await(); return authority.submit(a); });
            var fb = executor.submit(() -> { start.await(); return authority.submit(b); });
            start.countDown();
            var decisions = List.of(fa.get(10, TimeUnit.SECONDS), fb.get(10, TimeUnit.SECONDS));
            assertEquals(1, decisions.stream().filter(d -> d.outcome() == Outcome.COMMITTED).count());
            assertEquals(1, decisions.stream().filter(d -> d.outcome() == Outcome.REJECTED).count());
            var winner = decisions.stream().filter(d -> d.outcome() == Outcome.COMMITTED).findFirst().orElseThrow();
            assertEquals(winner.operation().update(), authority.read());
            for (var d : decisions) assertEquals(d, authority.lookup(d.operation().operationId()).orElseThrow());
        }
    }

    @Test void ordinarySqlCredentialsCannotResetStateForgeReceiptsOrSelectAnotherScope() throws Exception {
        var op = operation("one", AcceptedState.empty(), 1);
        assertEquals(Outcome.COMMITTED, authority.submit(op).outcome());
        try (var db = connect(); var statement = db.createStatement()) {
            for (String sql : List.of("UPDATE plugin_checkpoints.slots SET installation_hash=''",
                    "DELETE FROM plugin_checkpoints.operations",
                    "UPDATE plugin_checkpoints.operations SET outcome='COMMITTED'",
                    "INSERT INTO plugin_checkpoints.slots(installation_id,trust_domain,root_hash,client_role) "
                        + "VALUES('new','community','" + hash("roots") + "','" + role + "')")) {
                assertEquals("42501", assertThrows(SQLException.class, () -> statement.execute(sql)).getSQLState());
            }
        }
        var foreign = new PostgresPluginCheckpointAuthority(this::connect,
            new Scope("foreign", "community", hash("roots")), 3);
        assertThrows(java.io.IOException.class, foreign::read);
        var wrongRoots = new PostgresPluginCheckpointAuthority(this::connect,
            new Scope(scope.installationId(), "community", hash("other-roots")), 3);
        assertThrows(java.io.IOException.class, wrongRoots::read);
        assertEquals(op.update(), authority.read());
    }

    @Test void serverRejectsIdReuseAndDirectSqlTrustRegressionWithoutDependingOnJavaValidation() throws Exception {
        var op = operation("one", AcceptedState.empty(), 1);
        assertEquals(Outcome.COMMITTED, authority.submit(op).outcome());
        var conflicting = operation("one", op.update(), 2);
        assertThrows(SecurityException.class, () -> authority.submit(conflicting));
        var second = operation("two", op.update(), 2);
        assertEquals(Outcome.COMMITTED, authority.submit(second).outcome());
        // Replace the complete checkpoint, including its valid native content hash, to isolate monotonicity.
        var proposed = operation("three", second.update(), 3);
        String raw = new String(proposed.canonicalBytes(), StandardCharsets.UTF_8);
        String invalid = raw.replace("\"sequence\":3", "\"sequence\":1")
            .replace(proposed.update().checkpoint().revisionHash(), op.update().checkpoint().revisionHash())
            .replace(proposed.update().checkpoint().contentHash(), op.update().checkpoint().contentHash());
        try (var db = connect(); var statement = db.prepareStatement("SELECT * FROM plugin_checkpoints.submit_operation(?)")) {
            statement.setString(1, invalid);
            var failure = assertThrows(SQLException.class, statement::execute);
            assertEquals("22023", failure.getSQLState());
            assertTrue(failure.getMessage().contains("checkpoint must be unchanged or immediate successor"));
        }
        assertEquals(second.update(), authority.read());
        assertTrue(authority.lookup("three").isEmpty());
    }

    @Test void unsafeRoleGrantsAreDetectedAndFullReceiptCapacityCannotOverwriteState() throws Exception {
        var one = operation("one", AcceptedState.empty(), 1);
        assertEquals(Outcome.COMMITTED, authority.submit(one).outcome());
        try (var db = admin(); var statement = db.createStatement()) {
            statement.execute("GRANT UPDATE ON plugin_checkpoints.slots TO " + role);
        }
        assertThrows(java.io.IOException.class, authority::read);
        try (var db = admin(); var statement = db.createStatement()) {
            statement.execute("REVOKE UPDATE ON plugin_checkpoints.slots FROM " + role);
            statement.execute("UPDATE plugin_checkpoints.slots SET maximum_operations=1 WHERE client_role='" + role + "'");
        }
        assertEquals(Outcome.OUTCOME_UNKNOWN, authority.submit(operation("two", one.update(), 2)).outcome());
        assertEquals(one.update(), authority.read());
        assertEquals(Outcome.COMMITTED, authority.lookup("one").orElseThrow().outcome());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "slots, installation_hash, INSERT, false", "slots, installation_hash, UPDATE, false",
        "operations, outcome, INSERT, false", "operations, outcome, UPDATE, false",
        "slots, installation_hash, INSERT, true", "slots, installation_hash, UPDATE, true",
        "operations, outcome, INSERT, true", "operations, outcome, UPDATE, true"
    })
    void columnWriteGrantsCannotQualifyAsAnExecuteOnlyAuthority(String table, String column,
            String privilege, boolean inherited) throws Exception {
        var one = operation("one", AcceptedState.empty(), 1);
        var originalDecision = authority.submit(one);
        assertEquals(Outcome.COMMITTED, originalDecision.outcome());
        String recipient = inherited ? role + "_writer" : role;
        String relation = "plugin_checkpoints." + table;
        try (var db = admin(); var statement = db.createStatement()) {
            if (inherited) {
                statement.execute("CREATE ROLE " + recipient);
                statement.execute("GRANT " + recipient + " TO " + role);
            }
            statement.execute("GRANT " + privilege + "(" + column + ") ON " + relation + " TO " + recipient);
        }
        try {
            // PostgreSQL distinguishes a column grant from the corresponding whole-table privilege.
            try (var db = connect(); var query = db.prepareStatement(
                    "SELECT pg_catalog.has_table_privilege(CAST(? AS name),CAST(? AS text),CAST(? AS text)), "
                        + "pg_catalog.has_any_column_privilege(CAST(? AS name),CAST(? AS text),CAST(? AS text))")) {
                query.setString(1, role); query.setString(2, relation); query.setString(3, privilege);
                query.setString(4, role); query.setString(5, relation); query.setString(6, privilege);
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertFalse(rows.getBoolean(1), "this control must not grant the table-level privilege");
                    assertTrue(rows.getBoolean(2), "the real client must hold the column privilege");
                }
            }
            assertThrows(java.io.IOException.class, authority::read);
            assertThrows(java.io.IOException.class, () -> authority.lookup("one"));
            assertEquals(Outcome.OUTCOME_UNKNOWN, authority.submit(operation("two", one.update(), 2)).outcome());
        } finally {
            try (var db = admin(); var statement = db.createStatement()) {
                statement.execute("REVOKE " + privilege + "(" + column + ") ON " + relation + " FROM " + recipient);
            }
        }
        assertEquals(one.update(), authority.read());
        assertEquals(originalDecision, authority.lookup("one").orElseThrow());
        assertTrue(authority.lookup("two").isEmpty(), "unsafe credentials must not create a decision");
    }

    @Test void realHttpsInstallUsesTheDatabaseAuthorityAndANewJvmCanRecoverItsReceipt(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try (var wire = new SignedPackageServer()) {
            scope = new Scope(scope.installationId(), "community", hash(wire.roots.toCanonicalJson()));
            try (var db = admin(); var update = db.prepareStatement(
                    "UPDATE plugin_checkpoints.slots SET root_hash=? WHERE client_role=?")) {
                update.setString(1, scope.rootTrustStoreHash()); update.setString(2, role); update.executeUpdate();
            }
            authority = new PostgresPluginCheckpointAuthority(this::connect, scope, 3);
            try (var transport = new PluginDistributionTransport(Set.of(wire.origin), java.time.Duration.ofSeconds(2),
                    java.time.Duration.ofSeconds(3), false, wire.tls)) {
                var limits = new PluginDistributionClient.Limits(65536,65536,1048576,16,64);
                var client = new PluginDistributionTransactions(directory.resolve("packages"), wire.roots, authority, transport, limits);
                var request = PluginArtifactResolver.ResolutionRequest.exact("synthetic-install",
                    PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN,"synthetic","1.0.0","0.5.0","1",List.of("algebra"));
                var result = client.install("real-https", wire.sources, request);
                assertEquals(Outcome.COMMITTED, result.outcome());
                var reopened = new PluginDistributionTransactions(directory.resolve("packages"), wire.roots, authority, transport, limits);
                assertEquals(result.decision(), reopened.recover("real-https").decision());
                assertArrayEquals(wire.jar, reopened.readArtifact(result.installation().artifacts().getFirst().identityHash()));
                var child = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")), Reopen.class.getName())
                    .redirectErrorStream(true);
                child.environment().put("SYNTHETIC_DB",url()); child.environment().put("SYNTHETIC_ROLE",role);
                child.environment().put("SYNTHETIC_ROOT",scope.rootTrustStoreHash());
                var process = child.start();
                if (!process.waitFor(15,TimeUnit.SECONDS)) {
                    process.destroyForcibly(); fail("separate checkpoint client did not finish within the deadline");
                }
                String output = new String(process.getInputStream().readNBytes(16385),StandardCharsets.UTF_8);
                assertEquals(0,process.exitValue(),output);
                assertTrue(output.contains("COMMITTED " + result.installation().contentHash()),output);
                // Restoring/deleting the local cache never resets the remote selected pair.
                java.nio.file.Files.writeString(directory.resolve("packages/generations/"
                    + result.installation().contentHash().substring(7) + "/installation.json"), "{}");
                assertThrows(SecurityException.class,reopened::active);
                assertEquals(Outcome.COMMITTED,reopened.recover("real-https").outcome());
                assertEquals("MISSING_OR_INVALID",reopened.recover("real-https").localEvidenceStatus());
            }
        }
    }

    /** Separate process, test-only credentials; executes the actual JDBC provider. */
    public static class Reopen {
        public static void main(String[] args) throws Exception {
            String role = System.getenv("SYNTHETIC_ROLE");
            var scope = new Scope(role,"community",System.getenv("SYNTHETIC_ROOT"));
            var provider = new PostgresPluginCheckpointAuthority(
                () -> DriverManager.getConnection(System.getenv("SYNTHETIC_DB"),role,"synthetic-test-only"),scope,3);
            var receipt = provider.lookup("real-https").orElseThrow();
            System.out.println(receipt.outcome() + " " + receipt.operation().update().installationHash());
        }
    }

    /** Real bounded HTTPS and independently generated publisher/curator/authority signatures. Package-only fixture. */
    static final class SignedPackageServer implements AutoCloseable {
        final com.sun.net.httpserver.HttpsServer server;
        final javax.net.ssl.SSLContext tls;
        final java.net.URI origin;
        final PluginTrustStore roots;
        final PluginDistributionClient.Sources sources;
        final byte[] jar;
        SignedPackageServer() throws Exception {
            var keys = java.security.KeyStore.getInstance("PKCS12");
            var keyFile = java.nio.file.Path.of(System.getProperty("regelsuche.projectRoot"))
                .resolve("app/src/test/resources/plugin-distribution/loopback-test-only.p12");
            try (var input = java.nio.file.Files.newInputStream(keyFile)) {
                keys.load(input,"test-only-password".toCharArray());
            }
            var km = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            km.init(keys,"test-only-password".toCharArray());
            var tm = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tm.init(keys); tls = javax.net.ssl.SSLContext.getInstance("TLS");
            tls.init(km.getKeyManagers(),tm.getTrustManagers(),null);
            server = com.sun.net.httpserver.HttpsServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(tls));
            origin = java.net.URI.create("https://127.0.0.1:" + server.getAddress().getPort());
            var generator = java.security.KeyPairGenerator.getInstance("Ed25519");
            var root = generator.generateKeyPair(); var curator = generator.generateKeyPair(); var publisher = generator.generateKeyPair();
            roots = new PluginTrustStore(PluginTrustStore.SCHEMA,List.of(key("authority",root)),List.of());
            var working = new PluginTrustStore(PluginTrustStore.SCHEMA,List.of(key("curator",curator),key("publisher",publisher)),List.of());
            var bytes = new java.io.ByteArrayOutputStream();
            try (var output = new java.util.jar.JarOutputStream(bytes)) {
                var entry = new java.util.zip.ZipEntry("synthetic-content.txt"); entry.setTime(0);
                output.putNextEntry(entry); output.write("synthetic-package-only".getBytes(StandardCharsets.UTF_8)); output.closeEntry();
            }
            jar = bytes.toByteArray();
            String artifactHash = hashBytes(jar);
            var entry = PluginArtifactIndex.Entry.create("synthetic-1",PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN,
                "synthetic","1.0.0","1","0.5.0","",List.of("algebra"),List.of(),"synthetic.jar",artifactHash,
                origin.resolve("/synthetic.jar").toString(),origin.resolve("/synthetic.jar.sig.json").toString(),
                origin.resolve("/provenance.json").toString(),"publisher");
            var index = PluginArtifactIndex.create("synthetic-index","one","curator",List.of(entry));
            var artifactSignature = PluginSignatureManifest.create("synthetic.jar",artifactHash,"publisher","key",
                sign(publisher,PluginSignatureManifest.signedPayload("synthetic.jar",artifactHash,"publisher","key","Ed25519")));
            var indexSignature = PluginArtifactIndexSignature.create(index.indexId(),index.revision(),index.contentHash(),"curator","key",
                sign(curator,PluginArtifactIndexSignature.signedPayload(index.indexId(),index.revision(),index.contentHash(),"curator","key","Ed25519")));
            var revision = PluginTrustStoreRevision.create("community",1,"",hash(working.toCanonicalJson()),"authority","key",
                sign(root,PluginTrustStoreRevision.signedPayload("community",1,"",hash(working.toCanonicalJson()),"authority","key","Ed25519")));
            var unsigned = new PluginArtifactProvenance(PluginArtifactProvenance.SCHEMA,entry.identityHash(),artifactHash,
                entry.provenanceUri(),origin.resolve("/source").toString(),"synthetic",hash("source"),hash("recipe"),
                "publisher","key","Ed25519",Base64.getEncoder().encodeToString(new byte[64]));
            var provenance = new PluginArtifactProvenance(unsigned.schema(),unsigned.artifactIdentityHash(),unsigned.artifactSha256(),
                unsigned.provenanceUri(),unsigned.sourceUri(),unsigned.sourceRevision(),unsigned.sourceSha256(),
                unsigned.buildRecipeSha256(),unsigned.publisherId(),unsigned.keyId(),unsigned.algorithm(),sign(publisher,unsigned.signedPayload()));
            Map<String,byte[]> responses = new HashMap<>();
            responses.put("/synthetic.jar",jar);
            responses.put("/synthetic.jar.sig.json",artifactSignature.toCanonicalJson().getBytes(StandardCharsets.UTF_8));
            responses.put("/provenance.json",provenance.toCanonicalJson().getBytes(StandardCharsets.UTF_8));
            responses.put("/index.json",index.toCanonicalJson().getBytes(StandardCharsets.UTF_8));
            responses.put("/index.sig.json",indexSignature.toCanonicalJson().getBytes(StandardCharsets.UTF_8));
            responses.put("/trust.json",working.toCanonicalJson().getBytes(StandardCharsets.UTF_8));
            responses.put("/revision.json",revision.toCanonicalJson().getBytes(StandardCharsets.UTF_8));
            server.createContext("/",exchange -> {
                byte[] response = responses.get(exchange.getRequestURI().getPath());
                if (response == null) exchange.sendResponseHeaders(404,-1);
                else { exchange.sendResponseHeaders(200,response.length); exchange.getResponseBody().write(response); }
                exchange.close();
            });
            sources = new PluginDistributionClient.Sources(origin.resolve("/trust.json"),origin.resolve("/revision.json"),
                origin.resolve("/index.json"),origin.resolve("/index.sig.json"),index.indexId(),index.revision(),index.contentHash());
            server.start();
        }
        private static PluginTrustStore.PublisherKey key(String id,java.security.KeyPair key) {
            return new PluginTrustStore.PublisherKey(id,"key","Ed25519",Base64.getEncoder().encodeToString(key.getPublic().getEncoded()),
                PluginTrustStore.KeyStatus.ACTIVE,"");
        }
        private static String sign(java.security.KeyPair key,byte[] bytes) throws Exception {
            var signature = java.security.Signature.getInstance("Ed25519"); signature.initSign(key.getPrivate()); signature.update(bytes);
            return Base64.getEncoder().encodeToString(signature.sign());
        }
        private static String hashBytes(byte[] bytes) throws Exception {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        public void close() { server.stop(0); }
    }

    private Operation operation(String id, AcceptedState previous, long sequence) throws Exception {
        String revision = hash("revision-" + sequence);
        String json = "{\"schema\":\"regelsuche.plugin-trust-store-chain-checkpoint/v1\",\"trustDomainId\":\"community\","
            + "\"sequence\":" + sequence + ",\"revisionHash\":\"" + revision + "\"}\n";
        var checkpoint = new PluginTrustStoreRevisionVerifier.ChainCheckpoint(
            "regelsuche.plugin-trust-store-chain-checkpoint/v1", "community", sequence, revision, hash(json));
        return new Operation(scope, id, hash("intent-" + id), previous, new AcceptedState(hash("generation-" + id), checkpoint));
    }
    private Connection connect() throws SQLException { return DriverManager.getConnection(url(), role, "synthetic-test-only"); }
    private static Connection admin() throws SQLException { return DriverManager.getConnection(url(), "regelsuche", "regelsuche-demo"); }
    private static String url() { return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/regelsuche"; }
    private static String hash(String text) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
