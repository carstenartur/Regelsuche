package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.plugin.PluginArtifactVerification.Status;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginArtifactTrustSymlinkTest {
    @Test
    void jarSymlinkIsRetainedAsBlockedUnreadableEvidence(@TempDir Path tempDir)
        throws Exception {
        Path source = tempDir.resolve("plugins");
        Path staging = tempDir.resolve("staging");
        Files.createDirectories(source);
        Path target = tempDir.resolve("outside.jar");
        Files.writeString(target, "not an admitted plugin\n", StandardCharsets.UTF_8);
        Path link = source.resolve("linked.jar");
        Files.createSymbolicLink(link, target);

        PluginArtifactGate.GateResult result = new PluginArtifactGate(
            PluginTrustStore.empty(),
            PluginTrustPolicy.WARN
        ).materialize(source, staging);

        assertTrue(result.admittedArtifacts().isEmpty());
        assertEquals(List.of("linked.jar"), result.blockedArtifacts());
        assertEquals(1, result.verifications().size());
        PluginArtifactVerification verification = result.verifications().getFirst();
        assertEquals(Status.UNREADABLE, verification.status());
        assertEquals(List.of("ARTIFACT_NOT_A_REGULAR_FILE"), verification.warnings());
        assertFalse(Files.exists(staging.resolve("linked.jar")));
    }
}
