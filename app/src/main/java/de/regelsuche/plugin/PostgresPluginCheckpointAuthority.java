package de.regelsuche.plugin;

import de.regelsuche.plugin.PluginCheckpointTransactions.*;
import de.regelsuche.plugin.PluginCheckpointAuthority.AcceptedState;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * JDBC adapter for the separately provisioned, EXECUTE-only PostgreSQL authority.
 * A database transaction commits the whole selected pair and the operation receipt.
 * Database/backup administrators remain outside this protection boundary.
 */
public final class PostgresPluginCheckpointAuthority implements PluginCheckpointTransactions {
    @FunctionalInterface public interface Connections {
        /** Supply a fresh authenticated connection with bounded connection/TLS establishment. */
        Connection open() throws SQLException;
    }

    private final Connections connections;
    private final Scope scope;
    private final int timeoutSeconds;

    public PostgresPluginCheckpointAuthority(Connections connections, Scope scope, int timeoutSeconds) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.scope = Objects.requireNonNull(scope, "scope");
        if (timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("authority timeout must be 1..60 seconds");
        this.timeoutSeconds = timeoutSeconds;
    }

    /** Strict operator factory: credentials and TLS policy cannot be supplied through URL query parameters. */
    public static PostgresPluginCheckpointAuthority connect(String jdbcUrl, String user, String password,
            Path tlsRootCertificate, Scope scope, int timeoutSeconds) {
        Objects.requireNonNull(jdbcUrl, "JDBC URL");
        if (!jdbcUrl.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("absolute PostgreSQL URL required");
        URI uri = URI.create(jdbcUrl.substring(5));
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535
                || uri.getPath() == null || !uri.getPath().matches("/[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("PostgreSQL URL must contain only host, optional port and database");
        }
        PluginCheckpointTransactions.identifier(user);
        if (password == null || password.isEmpty() || password.length() > 16384) throw new IllegalArgumentException("authority password required");
        Objects.requireNonNull(tlsRootCertificate, "explicit PostgreSQL TLS root certificate");
        Properties properties = new Properties();
        properties.setProperty("user", user); properties.setProperty("password", password);
        properties.setProperty("sslmode", "verify-full");
        properties.setProperty("sslrootcert", tlsRootCertificate.toAbsolutePath().toString());
        properties.setProperty("connectTimeout", Integer.toString(timeoutSeconds));
        properties.setProperty("loginTimeout", Integer.toString(timeoutSeconds));
        properties.setProperty("socketTimeout", Integer.toString(timeoutSeconds));
        properties.setProperty("cancelSignalTimeout", Integer.toString(timeoutSeconds));
        properties.setProperty("sslResponseTimeout", Integer.toString(Math.multiplyExact(timeoutSeconds, 1000)));
        return new PostgresPluginCheckpointAuthority(() -> DriverManager.getConnection(jdbcUrl, properties), scope, timeoutSeconds);
    }

    @Override public Scope scope() { return scope; }

    @Override public AcceptedState read() throws IOException {
        try (Connection db = open(); var query = db.prepareStatement("SELECT plugin_checkpoints.read_state(?,?,?)")) {
            bindScope(query); query.setQueryTimeout(timeoutSeconds);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new IOException("checkpoint scope is unavailable");
                var state = PluginDistributionJson.read(text(rows, 1).getBytes(StandardCharsets.UTF_8), AcceptedState.class);
                if (rows.next() || state.checkpoint() != null && !scope.trustDomain().equals(state.checkpoint().trustDomainId())) {
                    throw new SecurityException("foreign or ambiguous checkpoint state");
                }
                return state;
            }
        } catch (SQLException | IllegalArgumentException failure) {
            throw new IOException("checkpoint authority read failed", failure);
        }
    }

    @Override public Optional<Decision> lookup(String id) throws IOException {
        PluginCheckpointTransactions.identifier(id);
        try (Connection db = open(); var query = db.prepareStatement("SELECT * FROM plugin_checkpoints.lookup_operation(?,?,?,?)")) {
            bindScope(query); query.setString(4, id); query.setQueryTimeout(timeoutSeconds);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                var decision = decision(rows, id);
                if (rows.next()) throw new IOException("ambiguous checkpoint decision");
                return Optional.of(decision);
            }
        } catch (SQLException | IllegalArgumentException failure) {
            throw new IOException("checkpoint operation lookup failed", failure);
        }
    }

    @Override public Decision submit(Operation operation) throws IOException {
        Objects.requireNonNull(operation, "operation");
        if (!scope.equals(operation.scope())) throw new SecurityException("operation belongs to another authority scope");
        byte[] bytes = operation.canonicalBytes();
        if (bytes.length > Operation.MAX_BYTES) throw new IllegalArgumentException("operation byte limit");
        Connection db = null;
        try {
            db = open();
            db.setAutoCommit(false);
            try (var settings = db.createStatement()) {
                settings.setQueryTimeout(timeoutSeconds);
                settings.execute("SET LOCAL synchronous_commit = on");
            }
            Decision decision;
            try (var query = db.prepareStatement("SELECT * FROM plugin_checkpoints.submit_operation(?)")) {
                query.setQueryTimeout(timeoutSeconds);
                query.setString(1, new String(bytes, StandardCharsets.UTF_8));
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw new IOException("missing authority decision");
                    decision = decision(rows, operation.operationId());
                    if (rows.next() || !operation.equals(decision.operation())) {
                        throw new IOException("authority decision differs from complete operation");
                    }
                }
            }
            db.commit(); // Only an acknowledged commit makes this response terminal.
            return decision;
        } catch (SQLException failure) {
            if ("23505".equals(failure.getSQLState())) {
                throw new SecurityException("checkpoint operation ID already belongs to different inputs");
            }
            return new Decision(operation, Outcome.OUTCOME_UNKNOWN);
        } catch (IOException | IllegalArgumentException failure) {
            return new Decision(operation, Outcome.OUTCOME_UNKNOWN);
        } finally {
            if (db != null) {
                // Closing an uncommitted connection rolls back; a confirmed receipt is not undone by close failure.
                try { db.close(); } catch (SQLException ignored) { }
            }
        }
    }

    private Connection open() throws SQLException {
        Connection db = connections.open();
        try {
            db.setNetworkTimeout(Runnable::run, timeoutSeconds * 1000);
            return db;
        } catch (SQLException failure) {
            try { db.close(); } catch (SQLException ignored) { }
            throw failure;
        }
    }

    private void bindScope(PreparedStatement query) throws SQLException {
        query.setString(1, scope.installationId()); query.setString(2, scope.trustDomain());
        query.setString(3, scope.rootTrustStoreHash());
    }

    private Decision decision(ResultSet rows, String id) throws SQLException, IOException {
        Operation operation = Operation.read(text(rows, 1).getBytes(StandardCharsets.UTF_8));
        String status = text(rows, 2);
        if (!scope.equals(operation.scope()) || !id.equals(operation.operationId())
                || !(status.equals("COMMITTED") || status.equals("REJECTED"))) {
            throw new IOException("invalid authority decision");
        }
        return new Decision(operation, Outcome.valueOf(status));
    }

    private static String text(ResultSet rows, int column) throws SQLException, IOException {
        try (Reader input = rows.getCharacterStream(column)) {
            if (input == null) throw new IOException("null authority field");
            StringBuilder value = new StringBuilder();
            char[] block = new char[1024];
            for (int count; (count = input.read(block)) >= 0;) {
                if (count > Operation.MAX_BYTES - value.length()) throw new IOException("authority response exceeds limit");
                value.append(block, 0, count);
            }
            String text = value.toString();
            if (text.getBytes(StandardCharsets.UTF_8).length > Operation.MAX_BYTES) throw new IOException("authority byte limit");
            return text;
        }
    }
}
