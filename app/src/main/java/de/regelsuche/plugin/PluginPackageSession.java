package de.regelsuche.plugin;

import de.regelsuche.plugin.PluginCheckpointTransactions.Scope;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Owns the configured package transport for one synchronous command. */
final class PluginPackageSession implements AutoCloseable {
    private final Scope scope;
    private final PluginDistributionTransactions transactions;
    private final Closeable resource;

    // Package-local composition seam: only external construction is replaced in fixtures.
    PluginPackageSession(Scope scope, PluginDistributionTransactions transactions, Closeable resource) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.resource = Objects.requireNonNull(resource, "owned resource");
    }

    static PluginPackageSession open(PluginPackageCommand.Config config, Map<String, String> environment) throws IOException {
        String password = environment.get(config.authority().passwordEnvironment());
        var scope = new Scope(config.installationId(), config.trustDomain(),
            PluginDistributionJson.hash(config.pinnedRoots().toCanonicalJson()));
        var authority = PostgresPluginCheckpointAuthority.connect(config.authority().jdbcUrl(), config.authority().user(),
            password, PluginPackageCommand.absolute(config.authority().tlsRootCertificate()), scope,
            config.authority().timeoutSeconds());
        var transport = new PluginDistributionTransport(config.allowedOrigins(),
            Duration.ofSeconds(config.connectTimeoutSeconds()), Duration.ofSeconds(config.responseTimeoutSeconds()), false, null);
        try {
            var transactions = new PluginDistributionTransactions(PluginPackageCommand.absolute(config.packageDirectory()),
                config.pinnedRoots(), authority, transport, config.limits());
            return new PluginPackageSession(scope, transactions, transport::close);
        } catch (IOException | RuntimeException | Error failure) {
            transport.close();
            throw failure;
        }
    }

    Scope scope() { return scope; }
    PluginDistributionTransactions transactions() { return transactions; }
    @Override public void close() throws IOException { resource.close(); }
}
