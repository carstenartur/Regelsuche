package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginPackageCommandTest {
    @TempDir Path directory;
    @Test void realJdbcConnectionFailureIsUnknownRecoveryAndNeverPrintsTheEnvironmentSecret() throws Exception {
        try (var f = new PluginDistributionFixtures()) {
            Path config = configuration(f);
            var output = new ByteArrayOutputStream();
            int exit = PluginPackageCommand.run(new String[]{"recover", "--config", config.toString(),
                "--operation", "operator-retained-id"}, new PrintStream(output),
                Map.of("SYNTHETIC_PG_PASSWORD", "never-print-this-synthetic-secret"));
            assertEquals(3, exit, output::toString);
            assertTrue(output.toString().contains("\"outcome\":\"OUTCOME_UNKNOWN\""));
            assertTrue(output.toString().contains("operator-retained-id"));
            assertFalse(output.toString().contains("never-print"));
            assertFalse(output.toString().contains("jdbc:"));
            assertFalse(output.toString().contains("\"installationHash\":\"\""), "unavailable must not become genesis");
        }
    }
    @Test void ambiguousOrOversizedConfigurationCannotConstructAClient() throws Exception {
        try (var f = new PluginDistributionFixtures()) {
            Path config = configuration(f);
            String original = Files.readString(config);
            for (String invalid : List.of(original.replace("\"timeoutSeconds\":1", "\"timeoutSeconds\":\"1\""),
                    original.replace("\"timeoutSeconds\":1", "\"timeoutSeconds\":1,\"timeoutSeconds\":2"),
                    original + " ".repeat(262144))) {
                Files.writeString(config, invalid);
                var output = new ByteArrayOutputStream();
                assertEquals(2, PluginPackageCommand.run(new String[]{"recover", "--config", config.toString(),
                    "--operation", "one"}, new PrintStream(output), Map.of("SYNTHETIC_PG_PASSWORD", "dummy")));
                assertFalse(Files.exists(directory.resolve("packages")));
            }
            Files.writeString(config, original);
            Path link = directory.resolve("config-link");
            Files.createSymbolicLink(link, config.getFileName());
            assertEquals(2, PluginPackageCommand.run(new String[]{"recover", "--config", link.toString(),
                "--operation", "one"}, new PrintStream(new ByteArrayOutputStream()), Map.of()));
        }
    }
    @Test void jdbcFactoryRejectsUrlPolicyOverridesAndMissingCredentialsBeforeConnection() throws Exception {
        var scope = new PluginCheckpointTransactions.Scope("slot", "community", PluginDistributionJson.hash("roots"));
        for (String url : List.of("jdbc:postgresql://host/db?sslmode=disable", "jdbc:postgresql://user:secret@host/db",
                "jdbc:postgresql://host/db#fragment", "jdbc:postgresql:db")) {
            assertThrows(IllegalArgumentException.class, () ->
                PostgresPluginCheckpointAuthority.connect(url, "client", "dummy", directory.resolve("root.crt"), scope, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> PostgresPluginCheckpointAuthority.connect(
            "jdbc:postgresql://host/db", "client", null, directory.resolve("root.crt"), scope, 1));
    }

    private Path configuration(PluginDistributionFixtures f) throws Exception {
        var value = new PluginPackageCommand.Config("regelsuche.plugin-package-config/v1",
            directory.resolve("packages").toString(), "cli-slot", "community", f.roots,
            new PluginPackageCommand.Authority("jdbc:postgresql://127.0.0.1:1/unused", "client",
                "SYNTHETIC_PG_PASSWORD", directory.resolve("explicit-root.crt").toString(), 1),
            Set.of(URI.create("https://catalog.example.org")), 1, 1,
            new PluginDistributionClient.Limits(65536, 65536, 1048576, 16, 64), null, null);
        Path path = directory.resolve("config.json");
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(path.toFile(), value);
        return path;
    }
}
