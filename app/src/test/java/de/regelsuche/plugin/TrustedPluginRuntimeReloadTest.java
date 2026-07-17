package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrustedPluginRuntimeReloadTest {
    @Test
    void malformedReplacementTrustStoreClosesRuntimeAndClearsOldEvidence(
        @TempDir Path tempDir
    ) throws Exception {
        Path plugins = tempDir.resolve("plugins");
        Path rules = tempDir.resolve("rules");
        Path trustStorePath = tempDir.resolve("trust-store.json");
        Files.createDirectories(plugins);
        Files.createDirectories(rules);
        PluginTrustStore.empty().write(trustStorePath);

        PluginRuntimeConfig runtimeConfig = new PluginRuntimeConfig(
            plugins,
            rules,
            false,
            Set.of(),
            Set.of()
        );
        PluginArtifactTrustConfig trustConfig = new PluginArtifactTrustConfig(
            trustStorePath,
            PluginTrustPolicy.REQUIRE_VERIFIED
        );

        try (TrustedPluginRuntime runtime = TrustedPluginRuntime.open(
            runtimeConfig,
            trustConfig
        )) {
            assertDoesNotThrow(runtime::runtime);
            assertDoesNotThrow(runtime::gateResult);

            Files.writeString(
                trustStorePath,
                "{not-valid-json}\n",
                StandardCharsets.UTF_8
            );

            assertThrows(IllegalStateException.class, runtime::reload);
            assertThrows(IllegalStateException.class, runtime::runtime);
            assertThrows(IllegalStateException.class, runtime::gateResult);
        }
    }
}
