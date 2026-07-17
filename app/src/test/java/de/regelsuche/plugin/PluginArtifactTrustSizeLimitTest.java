package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.plugin.PluginArtifactVerification.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginArtifactTrustSizeLimitTest {
    @Test
    void oversizedArtifactIsBlockedBeforeSnapshotAllocation(@TempDir Path tempDir)
        throws Exception {
        Path plugins = tempDir.resolve("plugins");
        Path staging = tempDir.resolve("staging");
        Files.createDirectories(plugins);
        Files.write(plugins.resolve("oversized.jar"), new byte[] {1, 2, 3, 4, 5});

        PluginArtifactGate.GateResult result = new PluginArtifactGate(
            PluginTrustStore.empty(),
            PluginTrustPolicy.WARN,
            4
        ).materialize(plugins, staging);

        assertTrue(result.admittedArtifacts().isEmpty());
        assertEquals(List.of("oversized.jar"), result.blockedArtifacts());
        PluginArtifactVerification verification = result.verifications().getFirst();
        assertEquals(Status.ARTIFACT_TOO_LARGE, verification.status());
        assertEquals(List.of("ARTIFACT_EXCEEDS_MAX_BYTES"), verification.warnings());
        assertFalse(verification.readable());
        assertFalse(Files.exists(staging.resolve("oversized.jar")));
    }
}
