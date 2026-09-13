package de.regelsuche.plugin;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.plugin.PluginCheckpointTransactions.Scope;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Explicit package CLI; no discovery/latest selection or runtime activation. */
public final class PluginPackageCommand {
    private static final int CONFIG_BYTES = 262144;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = JsonMapper.builder(
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();

    private PluginPackageCommand() { }

    public static int run(String[] args, PrintStream out, Map<String, String> environment) {
        try {
            if (args.length < 1 || !Set.of("install", "status", "recover", "remove", "rollback").contains(args[0])) {
                throw new IllegalArgumentException("package action required");
            }
            String action = args[0];
            Map<String, String> options = options(args);
            String operation = options.get("operation");
            if (!action.equals("status")) PluginCheckpointTransactions.identifier(operation);
            if (action.equals("status") && operation != null || !action.equals("rollback") && options.containsKey("target")) {
                throw new IllegalArgumentException("irrelevant package option");
            }
            if (action.equals("rollback")) PluginSignatureManifest.requireSha256(options.get("target"), "target");
            var config = JSON.readValue(read(Path.of(Objects.requireNonNull(options.get("config"), "explicit config"))), Config.class);
            if (config == null) throw new IllegalArgumentException("null package config");
            String password = environment.get(config.authority().passwordEnvironment());
            var scope = new Scope(config.installationId(), config.trustDomain(),
                PluginDistributionJson.hash(config.pinnedRoots().toCanonicalJson()));
            var authority = PostgresPluginCheckpointAuthority.connect(config.authority().jdbcUrl(), config.authority().user(),
                password, absolute(config.authority().tlsRootCertificate()), scope, config.authority().timeoutSeconds());
            try (var transport = new PluginDistributionTransport(config.allowedOrigins(),
                    Duration.ofSeconds(config.connectTimeoutSeconds()), Duration.ofSeconds(config.responseTimeoutSeconds()), false, null)) {
                var client = new PluginDistributionTransactions(absolute(config.packageDirectory()),
                    config.pinnedRoots(), authority, transport, config.limits());
                if (action.equals("status")) {
                    var response = new LinkedHashMap<String, Object>();
                    response.put("schema", "regelsuche.plugin-package-status/v1");
                    response.put("scope", scope); response.put("installation", client.active().orElse(null));
                    out.print(PluginDistributionJson.canonical(response));
                    return 0;
                }
                var result = switch (action) {
                    case "install" -> client.install(operation, Objects.requireNonNull(config.sources(), "exact sources"),
                        Objects.requireNonNull(config.selection(), "exact selection").request());
                    case "remove" -> client.remove(operation);
                    case "rollback" -> client.rollback(operation, options.get("target"));
                    case "recover" -> client.recover(operation);
                    default -> throw new IllegalStateException("unknown package action");
                };
                out.print(result.toCanonicalJson());
                return switch (result.outcome()) {
                    case COMMITTED -> result.installation() == null ? 5 : 0;
                    case REJECTED -> 4;
                    case OUTCOME_UNKNOWN -> 3;
                };
            }
        } catch (IOException | RuntimeException failure) {
            // No config, JDBC URI, password, downloaded body or nested driver error is printed.
            out.println("PACKAGE_CONFIGURATION_ERROR_OR_UNAVAILABLE: " + failure.getClass().getSimpleName()
                + "; check explicit package configuration and authority availability; use recover with the original operation ID.");
            return 2;
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!Set.of("--config", "--operation", "--target").contains(args[i])
                    || i + 1 >= args.length || args[i + 1].isBlank() || args[i + 1].startsWith("--")
                    || values.putIfAbsent(args[i].substring(2), args[i + 1]) != null) {
                throw new IllegalArgumentException("package options must be explicit, unique key/value pairs");
            }
        }
        return values;
    }

    private static Path absolute(String value) {
        Path path = Path.of(Objects.requireNonNull(value, "absolute path"));
        if (!path.isAbsolute() || !path.normalize().equals(path)) throw new IllegalArgumentException("normalized absolute path required");
        return path;
    }

    private static byte[] read(Path file) throws IOException {
        Path path = file.toAbsolutePath().normalize();
        Path cursor = path.getRoot();
        for (Path part : path) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("configuration symlink rejected");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("regular configuration file required");
        try (var input = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ByteBuffer block = ByteBuffer.allocate(8192);
            for (int count; (count = input.read(block)) >= 0;) {
                if (count > CONFIG_BYTES - bytes.size()) throw new IOException("package configuration byte limit");
                bytes.write(block.array(), 0, count); block.clear();
            }
            return bytes.toByteArray();
        }
    }

    public record Config(String schema, String packageDirectory, String installationId, String trustDomain,
            PluginTrustStore pinnedRoots, Authority authority, Set<URI> allowedOrigins,
            int connectTimeoutSeconds, int responseTimeoutSeconds, PluginDistributionClient.Limits limits,
            PluginDistributionClient.Sources sources, Selection selection) {
        public Config {
            if (!"regelsuche.plugin-package-config/v1".equals(schema)) throw new IllegalArgumentException("unsupported package config");
            absolute(packageDirectory); PluginCheckpointTransactions.identifier(installationId);
            PluginCheckpointTransactions.identifier(trustDomain); Objects.requireNonNull(pinnedRoots, "pinned roots");
            if (pinnedRoots.keys().isEmpty()) throw new IllegalArgumentException("pinned authority roots required");
            Objects.requireNonNull(authority, "authority"); Objects.requireNonNull(limits, "finite limits");
            allowedOrigins = Set.copyOf(Objects.requireNonNull(allowedOrigins, "allowed origins"));
            if (connectTimeoutSeconds < 1 || connectTimeoutSeconds > 60 || responseTimeoutSeconds < 1
                    || responseTimeoutSeconds > 120) throw new IllegalArgumentException("bounded transport deadlines required");
        }
    }

    public record Authority(String jdbcUrl, String user, String passwordEnvironment, String tlsRootCertificate, int timeoutSeconds) {
        public Authority {
            Objects.requireNonNull(jdbcUrl, "JDBC URL"); PluginCheckpointTransactions.identifier(user);
            if (passwordEnvironment == null || !passwordEnvironment.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw new IllegalArgumentException("explicit password environment variable required");
            }
            absolute(tlsRootCertificate);
            if (timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("bounded authority deadline required");
        }
    }

    public record Selection(String requestId, String componentId, String version, String coreVersion,
            String apiVersion, List<String> requiredCapabilities) {
        public Selection {
            requiredCapabilities = List.copyOf(Objects.requireNonNull(requiredCapabilities, "capabilities"));
            PluginArtifactResolver.ResolutionRequest.exact(requestId, PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN,
                componentId, version, coreVersion, apiVersion, requiredCapabilities);
        }
        PluginArtifactResolver.ResolutionRequest request() {
            return PluginArtifactResolver.ResolutionRequest.exact(requestId, PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN,
                componentId, version, coreVersion, apiVersion, requiredCapabilities);
        }
    }
}
