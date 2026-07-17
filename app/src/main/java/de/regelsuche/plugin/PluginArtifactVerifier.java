package de.regelsuche.plugin;

import de.regelsuche.plugin.PluginArtifactVerification.Status;
import de.regelsuche.plugin.PluginTrustStore.PublisherKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Verifies detached Ed25519 signatures before plugin bytecode is loaded. */
public final class PluginArtifactVerifier {
    private final PluginTrustStore trustStore;

    public PluginArtifactVerifier(PluginTrustStore trustStore) {
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
    }

    public PluginArtifactVerification verify(Path artifact) {
        return snapshot(artifact).verification();
    }

    /**
     * Reads the artifact once and returns the exact byte snapshot that was
     * verified. Admission gates must materialize these bytes rather than copy
     * the source path again, avoiding a verify-then-swap race.
     */
    public ArtifactSnapshot snapshot(Path artifact) {
        Objects.requireNonNull(artifact, "artifact");
        String artifactFileName = artifact.getFileName() == null
            ? artifact.toString()
            : artifact.getFileName().toString();
        if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
            return new ArtifactSnapshot(result(
                artifactFileName,
                "",
                "",
                Status.UNREADABLE,
                false,
                false,
                false,
                "",
                "",
                List.of("ARTIFACT_NOT_A_REGULAR_FILE")
            ), new byte[0]);
        }

        final byte[] artifactBytes;
        try {
            artifactBytes = Files.readAllBytes(artifact);
        } catch (IOException exception) {
            return new ArtifactSnapshot(result(
                artifactFileName,
                "",
                "",
                Status.UNREADABLE,
                false,
                false,
                false,
                "",
                "",
                List.of("ARTIFACT_UNREADABLE")
            ), new byte[0]);
        }

        String artifactHash = sha256(artifactBytes);
        Path manifestPath = PluginSignatureManifest.sidecarFor(artifact);
        String manifestFileName = manifestPath.getFileName().toString();
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            return new ArtifactSnapshot(result(
                artifactFileName,
                artifactHash,
                "",
                Status.MISSING_SIGNATURE_MANIFEST,
                false,
                false,
                false,
                "",
                "",
                List.of("MISSING_SIGNATURE_MANIFEST")
            ), artifactBytes);
        }

        final PluginSignatureManifest manifest;
        try {
            manifest = PluginSignatureManifest.read(manifestPath);
        } catch (RuntimeException exception) {
            return new ArtifactSnapshot(result(
                artifactFileName,
                artifactHash,
                manifestFileName,
                Status.MALFORMED_SIGNATURE_MANIFEST,
                true,
                false,
                false,
                "",
                "",
                List.of("MALFORMED_SIGNATURE_MANIFEST")
            ), artifactBytes);
        }

        if (!artifactFileName.equals(manifest.artifactFileName())) {
            return new ArtifactSnapshot(result(
                artifactFileName,
                artifactHash,
                manifestFileName,
                Status.MANIFEST_ARTIFACT_MISMATCH,
                true,
                false,
                false,
                manifest.publisherId(),
                manifest.keyId(),
                List.of("MANIFEST_ARTIFACT_MISMATCH")
            ), artifactBytes);
        }
        if (!artifactHash.equals(manifest.artifactSha256())) {
            return new ArtifactSnapshot(result(
                artifactFileName,
                artifactHash,
                manifestFileName,
                Status.ARTIFACT_HASH_MISMATCH,
                true,
                false,
                false,
                manifest.publisherId(),
                manifest.keyId(),
                List.of("ARTIFACT_HASH_MISMATCH")
            ), artifactBytes);
        }
        if (trustStore.isArtifactRevoked(artifactHash)) {
            return new ArtifactSnapshot(result(
                artifactFileName,
                artifactHash,
                manifestFileName,
                Status.REVOKED_ARTIFACT,
                true,
                false,
                false,
                manifest.publisherId(),
                manifest.keyId(),
                List.of("REVOKED_ARTIFACT")
            ), artifactBytes);
        }
        if (!trustStore.hasPublisher(manifest.publisherId())) {
            return new ArtifactSnapshot(result(
                artifactFileName,
                artifactHash,
                manifestFileName,
                Status.UNKNOWN_PUBLISHER,
                true,
                false,
                false,
                manifest.publisherId(),
                manifest.keyId(),
                List.of("UNKNOWN_PUBLISHER")
            ), artifactBytes);
        }

        Optional<PublisherKey> keyLookup = trustStore.findKey(manifest.publisherId(), manifest.keyId());
        if (keyLookup.isEmpty()) {
            return new ArtifactSnapshot(result(
                artifactFileName,
                artifactHash,
                manifestFileName,
                Status.UNKNOWN_KEY,
                true,
                false,
                false,
                manifest.publisherId(),
                manifest.keyId(),
                List.of("UNKNOWN_KEY")
            ), artifactBytes);
        }
        PublisherKey key = keyLookup.orElseThrow();
        if (key.revoked()) {
            return new ArtifactSnapshot(result(
                artifactFileName,
                artifactHash,
                manifestFileName,
                Status.REVOKED_KEY,
                true,
                false,
                false,
                manifest.publisherId(),
                manifest.keyId(),
                List.of("REVOKED_KEY")
            ), artifactBytes);
        }
        if (!manifest.algorithm().equals(key.algorithm())) {
            return new ArtifactSnapshot(result(
                artifactFileName,
                artifactHash,
                manifestFileName,
                Status.KEY_ALGORITHM_MISMATCH,
                true,
                false,
                false,
                manifest.publisherId(),
                manifest.keyId(),
                List.of("KEY_ALGORITHM_MISMATCH")
            ), artifactBytes);
        }
        if (!verifySignature(manifest, key)) {
            return new ArtifactSnapshot(result(
                artifactFileName,
                artifactHash,
                manifestFileName,
                Status.INVALID_SIGNATURE,
                true,
                false,
                false,
                manifest.publisherId(),
                manifest.keyId(),
                List.of("INVALID_SIGNATURE")
            ), artifactBytes);
        }

        Status verifiedStatus = key.retired()
            ? Status.VERIFIED_RETIRED_KEY
            : Status.VERIFIED_TRUSTED;
        List<String> warnings = key.retired()
            ? List.of("RETIRED_PUBLISHER_KEY")
            : List.of();
        return new ArtifactSnapshot(result(
            artifactFileName,
            artifactHash,
            manifestFileName,
            verifiedStatus,
            true,
            true,
            true,
            manifest.publisherId(),
            manifest.keyId(),
            warnings
        ), artifactBytes);
    }

    public static String sha256(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private boolean verifySignature(PluginSignatureManifest manifest, PublisherKey key) {
        try {
            Signature verifier = Signature.getInstance(PluginSignatureManifest.ALGORITHM);
            verifier.initVerify(key.publicKey());
            verifier.update(manifest.signedPayload());
            return verifier.verify(Base64.getDecoder().decode(manifest.signatureBase64()));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }

    private PluginArtifactVerification result(
        String artifactFileName,
        String artifactHash,
        String manifestFileName,
        Status status,
        boolean signaturePresent,
        boolean signatureVerified,
        boolean trusted,
        String publisherId,
        String keyId,
        List<String> warnings
    ) {
        return PluginArtifactVerification.create(
            artifactFileName,
            artifactHash,
            manifestFileName,
            status,
            signaturePresent,
            signatureVerified,
            trusted,
            publisherId,
            keyId,
            warnings
        );
    }

    public record ArtifactSnapshot(
        PluginArtifactVerification verification,
        byte[] artifactBytes
    ) {
        public ArtifactSnapshot {
            Objects.requireNonNull(verification, "verification");
            artifactBytes = artifactBytes == null ? new byte[0] : artifactBytes.clone();
        }

        @Override
        public byte[] artifactBytes() {
            return artifactBytes.clone();
        }
    }
}
