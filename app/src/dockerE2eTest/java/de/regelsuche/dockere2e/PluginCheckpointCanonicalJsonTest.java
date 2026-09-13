package de.regelsuche.dockere2e;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.plugin.PluginCheckpointAuthority.AcceptedState;
import de.regelsuche.plugin.PluginCheckpointTransactions.Operation;
import de.regelsuche.plugin.PluginCheckpointTransactions.Outcome;
import de.regelsuche.plugin.PluginCheckpointTransactions.Scope;
import de.regelsuche.plugin.PluginTrustStoreRevisionVerifier.ChainCheckpoint;
import de.regelsuche.plugin.PostgresPluginCheckpointAuthority;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Direct EXECUTE-only SQL must never persist a receipt that the real Java reader rejects. */
@Testcontainers
class PluginCheckpointCanonicalJsonTest {
    private static final String CHECKPOINT_SCHEMA = "regelsuche.plugin-trust-store-chain-checkpoint/v1";
    @Container static final GenericContainer<?> POSTGRES = PinnedPostgresContainer.create();
    private static final AtomicInteger IDS = new AtomicInteger();
    private Scope scope;
    private String role;
    private PostgresPluginCheckpointAuthority authority;

    @BeforeAll static void schema() throws Exception {
        try (var db = admin(); var statement = db.createStatement();
                var input = PluginCheckpointCanonicalJsonTest.class.getResourceAsStream(
                    "/plugin-distribution/checkpoint-postgresql-v1.sql")) {
            if (input == null) throw new IllegalStateException("missing real authority schema");
            statement.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @BeforeEach void provisionExecuteOnlyClient() throws Exception {
        role = "canonical_client_" + IDS.incrementAndGet();
        scope = new Scope(role, "Case-1.x_y", hash("roots"));
        try (var db = admin(); var ddl = db.createStatement()) {
            ddl.execute("CREATE ROLE " + role + " LOGIN PASSWORD 'synthetic-test-only'");
            ddl.execute("GRANT USAGE ON SCHEMA plugin_checkpoints TO " + role);
            ddl.execute("GRANT EXECUTE ON FUNCTION plugin_checkpoints.read_state(text,text,text), "
                + "plugin_checkpoints.lookup_operation(text,text,text,text), plugin_checkpoints.submit_operation(text) TO " + role);
            try (var insert = db.prepareStatement("INSERT INTO plugin_checkpoints.slots"
                    + "(installation_id,trust_domain,root_hash,client_role,maximum_operations) VALUES(?,?,?,?,8)")) {
                insert.setString(1, scope.installationId()); insert.setString(2, scope.trustDomain());
                insert.setString(3, scope.rootTrustStoreHash()); insert.setString(4, role);
                assertEquals(1, insert.executeUpdate());
            }
        }
        authority = new PostgresPluginCheckpointAuthority(this::connect, scope, 3);
    }

    static Stream<Arguments> noncanonicalRequests() {
        return Stream.of("LEADING_SPACE", "INNER_SPACE", "KEY_ORDER", "ESCAPED_ASCII", "ESCAPED_SLASH",
                "EXTRA_NEWLINE", "MISSING_NEWLINE", "DUPLICATE_ROOT", "DUPLICATE_NESTED", "EXPONENT")
            .flatMap(variant -> Stream.of(Arguments.of(variant, false), Arguments.of(variant, true)));
    }

    @ParameterizedTest(name = "{0}, existing checkpoint={1}")
    @MethodSource("noncanonicalRequests")
    void noncanonicalDirectSubmissionCannotConsumeAnIdChangeStateOrLeaveAnUnreadableReceipt(
            String variant, boolean existingCheckpoint) throws Exception {
        var initial = operation("initial", AcceptedState.empty(), 1);
        if (existingCheckpoint) assertEquals(Outcome.COMMITTED, authority.submit(initial).outcome());
        AcceptedState before = authority.read();
        int beforeCount = operationCount();
        var request = operation("review-Case_1.x", before, existingCheckpoint ? 2 : 1);
        String canonical = new String(request.canonicalBytes(), StandardCharsets.UTF_8);
        String changed = noncanonical(canonical, variant);
        assertFalse(canonical.equals(changed), "the control must really alter canonical bytes");
        assertThrows(IllegalArgumentException.class, () -> Operation.read(changed.getBytes(StandardCharsets.UTF_8)));
        try (var db = connect(); var equality = db.prepareStatement("SELECT CAST(? AS jsonb) = CAST(? AS jsonb)")) {
            equality.setString(1, canonical); equality.setString(2, changed);
            try (var rows = equality.executeQuery()) {
                assertTrue(rows.next()); assertTrue(rows.getBoolean(1), "same PostgreSQL semantic operation");
            }
            try (var submit = db.prepareStatement("SELECT * FROM plugin_checkpoints.submit_operation(?)")) {
                submit.setString(1, changed);
                var failure = assertThrows(SQLException.class, submit::execute,
                    "the authority must reject rather than store Java-unreadable receipt bytes");
                assertEquals("22023", failure.getSQLState());
            }
        }
        assertEquals(before, authority.read());
        assertEquals(beforeCount, operationCount());
        assertTrue(authority.lookup(request.operationId()).isEmpty());
        var decision = authority.submit(request);
        assertEquals(Outcome.COMMITTED, decision.outcome(), "rejected spelling must not consume the operation ID");
        assertEquals(decision, authority.lookup(request.operationId()).orElseThrow());
        assertArrayEquals(request.canonicalBytes(), decision.operation().canonicalBytes());
        assertEquals(beforeCount + 1, operationCount());
    }

    @Test void canonicalDirectOperationsRetainRecoverableBytesForAllStateTransitions() throws Exception {
        var first = operation("first", AcceptedState.empty(), 1);
        var second = operation("second", first.update(), 2);
        var unchangedTrust = new Operation(scope, "same-trust", hash("same-intent"), second.update(),
            new AcceptedState(hash("third-generation"), second.update().checkpoint()));
        var losingCas = operation("loser", AcceptedState.empty(), 1);
        for (var operation : List.of(first, second, unchangedTrust, losingCas)) {
            Outcome expected = operation == losingCas ? Outcome.REJECTED : Outcome.COMMITTED;
            byte[] bytes = operation.canonicalBytes();
            assertEquals(operation, Operation.read(bytes));
            try (var db = connect(); var submit = db.prepareStatement("SELECT * FROM plugin_checkpoints.submit_operation(?)")) {
                submit.setString(1, new String(bytes, StandardCharsets.UTF_8));
                try (var rows = submit.executeQuery()) {
                    assertTrue(rows.next());
                    assertArrayEquals(bytes, rows.getString(1).getBytes(StandardCharsets.UTF_8));
                    assertEquals(expected.name(), rows.getString(2));
                    assertFalse(rows.next());
                }
            }
            var recovered = authority.lookup(operation.operationId()).orElseThrow();
            assertEquals(expected, recovered.outcome());
            assertEquals(operation, recovered.operation());
            assertArrayEquals(bytes, recovered.operation().canonicalBytes());
            assertEquals(recovered, authority.submit(operation));
        }
        assertEquals(unchangedTrust.update(), authority.read());
        assertEquals(4, operationCount());
        assertEquals(first, authority.lookup(first.operationId()).orElseThrow().operation());
    }

    private static String noncanonical(String canonical, String variant) {
        String schema = "\"schema\":\"" + Operation.SCHEMA + "\"";
        return switch (variant) {
            case "LEADING_SPACE" -> " " + canonical;
            case "INNER_SPACE" -> canonical.replace("\":", "\": ");
            case "KEY_ORDER" -> "{" + schema + "," + canonical.substring(1).replace(schema + ",", "");
            case "ESCAPED_ASCII" -> canonical.replace("Case", "\\" + "u0043ase");
            case "ESCAPED_SLASH" -> canonical.replace("regelsuche/", "regelsuche\\/");
            case "EXTRA_NEWLINE" -> canonical + "\n";
            case "MISSING_NEWLINE" -> canonical.substring(0, canonical.length() - 1);
            case "DUPLICATE_ROOT" -> "{" + schema + "," + canonical.substring(1);
            case "DUPLICATE_NESTED" -> canonical.replace("\"sequence\":", "\"schema\":\"" + CHECKPOINT_SCHEMA + "\",\"sequence\":");
            case "EXPONENT" -> canonical.replaceAll("(\"sequence\":)([0-9]+)", "$1$2e0");
            default -> throw new IllegalArgumentException("unknown test mutation");
        };
    }

    private Operation operation(String id, AcceptedState previous, long sequence) throws Exception {
        String revision = hash("revision-" + sequence);
        String json = "{\"schema\":\"" + CHECKPOINT_SCHEMA + "\",\"trustDomainId\":\"" + scope.trustDomain()
            + "\",\"sequence\":" + sequence + ",\"revisionHash\":\"" + revision + "\"}\n";
        var checkpoint = new ChainCheckpoint(CHECKPOINT_SCHEMA, scope.trustDomain(), sequence, revision, hash(json));
        return new Operation(scope, id, hash("intent-" + id), previous, new AcceptedState(hash("generation-" + id), checkpoint));
    }

    private int operationCount() throws SQLException {
        try (var db = admin(); var query = db.prepareStatement(
                "SELECT operation_count FROM plugin_checkpoints.slots WHERE installation_id=? AND trust_domain=?")) {
            query.setString(1, scope.installationId()); query.setString(2, scope.trustDomain());
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getInt(1); }
        }
    }
    private Connection connect() throws SQLException { return DriverManager.getConnection(url(), role, "synthetic-test-only"); }
    private static Connection admin() throws SQLException { return DriverManager.getConnection(url(), "regelsuche", "regelsuche-demo"); }
    private static String url() { return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/regelsuche"; }
    private static String hash(String text) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
