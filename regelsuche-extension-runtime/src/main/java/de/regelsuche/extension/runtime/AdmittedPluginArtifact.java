package de.regelsuche.extension.runtime;

import de.regelsuche.api.StableApi;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable snapshot of exact plugin bytes admitted by a host policy. */
@StableApi(since = "2")
public final class AdmittedPluginArtifact {
    private final byte[] admittedBytes;
    private final String artifactSha256;
    private final String trustEvidenceSha256;
    private final String sourceReference;

    private AdmittedPluginArtifact(
        byte[] admittedBytes,
        String artifactSha256,
        String trustEvidenceSha256,
        String sourceReference
    ) {
        this.admittedBytes = admittedBytes.clone();
        this.artifactSha256 = requireHash(artifactSha256, "artifactSha256", false);
        this.trustEvidenceSha256 = requireHash(
            trustEvidenceSha256, "trustEvidenceSha256", true);
        this.sourceReference = sourceReference == null ? "" : sourceReference.trim();
        if (!sha256(this.admittedBytes).equals(this.artifactSha256)) {
            throw new IllegalArgumentException(
                "artifactSha256 does not match the admitted byte snapshot");
        }
    }

    /** Creates an immutable admission result and verifies its artifact hash. */
    public static AdmittedPluginArtifact of(
        byte[] admittedBytes,
        String artifactSha256,
        String trustEvidenceSha256,
        String sourceReference
    ) {
        return new AdmittedPluginArtifact(
            Objects.requireNonNull(admittedBytes, "admittedBytes"),
            artifactSha256,
            trustEvidenceSha256,
            sourceReference);
    }

    /** Returns a defensive copy of the exact admitted bytes. */
    public byte[] admittedBytes() {
        return admittedBytes.clone();
    }

    /** SHA-256 identity of the exact admitted byte snapshot. */
    public String artifactSha256() {
        return artifactSha256;
    }

    /** Host trust-evidence hash, empty for development admissions without such evidence. */
    public String trustEvidenceSha256() {
        return trustEvidenceSha256;
    }

    /** Human-auditable source reference retained in extension origin metadata. */
    public String sourceReference() {
        return sourceReference;
    }

    static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static String requireHash(String value, String name, boolean optional) {
        String normalized = value == null ? "" : value.trim();
        if (optional && normalized.isEmpty()) {
            return "";
        }
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be sha256:<64 lowercase hex>");
        }
        return normalized;
    }
}
