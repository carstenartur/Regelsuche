package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.plugin.PluginArtifactVerification.Status;
import de.regelsuche.plugin.PluginTrustStore.KeyStatus;
import de.regelsuche.plugin.PluginTrustStore.PublisherKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginArtifactTrustManifestFailureTest {
    private static final String PUBLIC_KEY_BASE64 =
        "MCowBQYDK2VwAyEAH0nHmwIVDzgfBwZMgSccK3RZqKbROm8/OPg5KGpDcZk=";
    private static final String PUBLISHER = "org.example.publisher";
    private static final String KEY_ID = "release-2026";

    @Test
    void malformedSidecarIsExplicitAndBlockedByStrictPolicy(@TempDir Path tempDir)
        throws Exception {
        Path artifact = writeArtifact(tempDir, "malformed.jar");
        Files.writeString(
            PluginSignatureManifest.sidecarFor(artifact),
            "{not-json}\n",
            StandardCharsets.UTF_8
        );

        PluginArtifactVerification verification = new PluginArtifactVerifier(
            PluginTrustStore.empty()
        ).verify(artifact);

        assertEquals(Status.MALFORMED_SIGNATURE_MANIFEST, verification.status());
        assertFalse(verification.permittedBy(PluginTrustPolicy.REQUIRE_VERIFIED));
        assertTrue(verification.permittedBy(PluginTrustPolicy.WARN));
    }

    @Test
    void manifestCannotBeReusedAfterArtifactRename(@TempDir Path tempDir)
        throws Exception {
        Path artifact = writeArtifact(tempDir, "renamed.jar");
        String hash = PluginArtifactVerifier.sha256(Files.readAllBytes(artifact));
        PluginSignatureManifest.create(
            "original.jar",
            hash,
            PUBLISHER,
            KEY_ID,
            zeroSignature()
        ).write(PluginSignatureManifest.sidecarFor(artifact));

        PluginArtifactVerification verification = new PluginArtifactVerifier(
            PluginTrustStore.empty()
        ).verify(artifact);

        assertEquals(Status.MANIFEST_ARTIFACT_MISMATCH, verification.status());
        assertFalse(verification.trusted());
    }

    @Test
    void matchingManifestWithInvalidSignatureIsRejected(@TempDir Path tempDir)
        throws Exception {
        Path artifact = writeArtifact(tempDir, "invalid-signature.jar");
        String hash = PluginArtifactVerifier.sha256(Files.readAllBytes(artifact));
        PluginSignatureManifest.create(
            artifact.getFileName().toString(),
            hash,
            PUBLISHER,
            KEY_ID,
            zeroSignature()
        ).write(PluginSignatureManifest.sidecarFor(artifact));
        PluginTrustStore trustStore = new PluginTrustStore(
            PluginTrustStore.SCHEMA,
            List.of(new PublisherKey(
                PUBLISHER,
                KEY_ID,
                PluginSignatureManifest.ALGORITHM,
                PUBLIC_KEY_BASE64,
                KeyStatus.ACTIVE,
                ""
            )),
            List.of()
        );

        PluginArtifactVerification verification = new PluginArtifactVerifier(
            trustStore
        ).verify(artifact);

        assertEquals(Status.INVALID_SIGNATURE, verification.status());
        assertFalse(verification.signatureVerified());
        assertFalse(verification.trusted());
        assertFalse(verification.permittedBy(PluginTrustPolicy.REQUIRE_VERIFIED));
    }

    private Path writeArtifact(Path directory, String fileName) throws Exception {
        Path artifact = directory.resolve(fileName);
        Files.write(artifact, "signed artifact bytes\n".getBytes(StandardCharsets.UTF_8));
        return artifact;
    }

    private String zeroSignature() {
        return Base64.getEncoder().encodeToString(new byte[64]);
    }
}
