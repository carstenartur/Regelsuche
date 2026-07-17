package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.plugin.PluginTrustStoreRevisionVerifier.ChainCheckpoint;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginTrustStoreRevisionStrictJsonTest {
    @Test
    void rejectsDuplicateRevisionFields(@TempDir Path tempDir) throws Exception {
        Path revision = tempDir.resolve("revision.json");
        Files.writeString(
            revision,
            """
            {
              "schema":"regelsuche.plugin-trust-store-revision/v1",
              "schema":"regelsuche.plugin-trust-store-revision/v1",
              "trustDomainId":"community-trust",
              "sequence":1,
              "previousRevisionHash":"",
              "trustStoreContentHash":"sha256:%s",
              "authorityId":"trust-authority",
              "keyId":"trust-root-2026",
              "algorithm":"Ed25519",
              "signatureBase64":"%s",
              "contentHash":"sha256:%s"
            }
            """.formatted(
                "0".repeat(64),
                "A".repeat(86) + "==",
                "0".repeat(64)),
            StandardCharsets.UTF_8);

        assertThrows(
            IllegalArgumentException.class,
            () -> PluginTrustStoreRevision.read(revision));
    }

    @Test
    void rejectsDuplicateCheckpointFields(@TempDir Path tempDir) throws Exception {
        Path checkpoint = tempDir.resolve("checkpoint.json");
        Files.writeString(
            checkpoint,
            """
            {
              "schema":"regelsuche.plugin-trust-store-chain-checkpoint/v1",
              "trustDomainId":"community-trust",
              "sequence":1,
              "sequence":1,
              "revisionHash":"sha256:%s",
              "contentHash":"sha256:%s"
            }
            """.formatted("0".repeat(64), "0".repeat(64)),
            StandardCharsets.UTF_8);

        assertThrows(
            IllegalArgumentException.class,
            () -> ChainCheckpoint.read(checkpoint));
    }
}
