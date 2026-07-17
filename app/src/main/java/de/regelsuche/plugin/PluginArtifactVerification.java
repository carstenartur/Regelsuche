package de.regelsuche.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, replayable result of verifying one external plugin artifact. */
public record PluginArtifactVerification(
    String schema,
    String artifactFileName,
    String artifactSha256,
    String manifestFileName,
    Status status,
    boolean signaturePresent,
    boolean signatureVerified,
    boolean trusted,
    String publisherId,
    String keyId,
    List<String> warnings,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.plugin-artifact-verification/v1";

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    public PluginArtifactVerification {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported plugin artifact verification schema");
        }
        artifactFileName = requireArtifactFileName(artifactFileName);
        Objects.requireNonNull(status, "status");
        artifactSha256 = artifactSha256 == null ? "" : artifactSha256;
        if (!artifactSha256.isEmpty()) {
            artifactSha256 = PluginSignatureManifest.requireSha256(artifactSha256, "artifactSha256");
        }
        manifestFileName = manifestFileName == null ? "" : manifestFileName;
        publisherId = publisherId == null ? "" : publisherId;
        keyId = keyId == null ? "" : keyId;
        warnings = normalizeWarnings(warnings);
        if (trusted && !signatureVerified) {
            throw new IllegalArgumentException("trusted verification must have a valid signature");
        }
        if (signatureVerified && !signaturePresent) {
            throw new IllegalArgumentException("verified signature must be present");
        }
        boolean verifiedStatus = status == Status.VERIFIED_TRUSTED
            || status == Status.VERIFIED_RETIRED_KEY;
        if (verifiedStatus != signatureVerified || verifiedStatus != trusted) {
            throw new IllegalArgumentException("verification status and trust flags disagree");
        }
        String expectedHash = hash(canonicalMaterial(
            artifactFileName,
            artifactSha256,
            manifestFileName,
            status,
            signaturePresent,
            signatureVerified,
            trusted,
            publisherId,
            keyId,
            warnings
        ));
        if (!expectedHash.equals(contentHash)) {
            throw new IllegalArgumentException("plugin artifact verification contentHash mismatch");
        }
    }

    public static PluginArtifactVerification create(
        String artifactFileName,
        String artifactSha256,
        String manifestFileName,
        Status status,
        boolean signaturePresent,
        boolean signatureVerified,
        boolean trusted,
        String publisherId,
        String keyId,
        List<String> warnings
    ) {
        String normalizedArtifactFileName = requireArtifactFileName(artifactFileName);
        Objects.requireNonNull(status, "status");
        List<String> normalizedWarnings = normalizeWarnings(warnings);
        String normalizedArtifactHash = artifactSha256 == null ? "" : artifactSha256;
        if (!normalizedArtifactHash.isEmpty()) {
            normalizedArtifactHash = PluginSignatureManifest.requireSha256(
                normalizedArtifactHash, "artifactSha256");
        }
        String normalizedManifest = manifestFileName == null ? "" : manifestFileName;
        String normalizedPublisher = publisherId == null ? "" : publisherId;
        String normalizedKey = keyId == null ? "" : keyId;
        String contentHash = hash(canonicalMaterial(
            normalizedArtifactFileName,
            normalizedArtifactHash,
            normalizedManifest,
            status,
            signaturePresent,
            signatureVerified,
            trusted,
            normalizedPublisher,
            normalizedKey,
            normalizedWarnings
        ));
        return new PluginArtifactVerification(
            SCHEMA,
            normalizedArtifactFileName,
            normalizedArtifactHash,
            normalizedManifest,
            status,
            signaturePresent,
            signatureVerified,
            trusted,
            normalizedPublisher,
            normalizedKey,
            normalizedWarnings,
            contentHash
        );
    }

    public boolean readable() {
        return status != Status.UNREADABLE && status != Status.ARTIFACT_TOO_LARGE;
    }

    public boolean permittedBy(PluginTrustPolicy policy) {
        return Objects.requireNonNull(policy, "policy").permits(this);
    }

    public String toCanonicalJson() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", schema);
        payload.put("artifactFileName", artifactFileName);
        payload.put("artifactSha256", artifactSha256);
        payload.put("manifestFileName", manifestFileName);
        payload.put("status", status.name());
        payload.put("signaturePresent", signaturePresent);
        payload.put("signatureVerified", signatureVerified);
        payload.put("trusted", trusted);
        payload.put("publisherId", publisherId);
        payload.put("keyId", keyId);
        payload.put("warnings", warnings);
        payload.put("contentHash", contentHash);
        try {
            return JSON.writeValueAsString(payload) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize plugin artifact verification", exception);
        }
    }

    private static List<String> normalizeWarnings(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .sorted(Comparator.naturalOrder())
            .toList();
    }

    private static String requireArtifactFileName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("artifactFileName must not be blank");
        }
        return value;
    }

    private static byte[] canonicalMaterial(
        String artifactFileName,
        String artifactSha256,
        String manifestFileName,
        Status status,
        boolean signaturePresent,
        boolean signatureVerified,
        boolean trusted,
        String publisherId,
        String keyId,
        List<String> warnings
    ) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeField(output, "schema", SCHEMA);
                writeField(output, "artifactFileName", artifactFileName);
                writeField(output, "artifactSha256", artifactSha256);
                writeField(output, "manifestFileName", manifestFileName);
                writeField(output, "status", status.name());
                writeField(output, "signaturePresent", Boolean.toString(signaturePresent));
                writeField(output, "signatureVerified", Boolean.toString(signatureVerified));
                writeField(output, "trusted", Boolean.toString(trusted));
                writeField(output, "publisherId", publisherId);
                writeField(output, "keyId", keyId);
                output.writeInt(warnings.size());
                for (String warning : warnings) {
                    writeField(output, "warning", warning);
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode plugin artifact verification", exception);
        }
    }

    private static void writeField(DataOutputStream output, String name, String value) throws IOException {
        writeBytes(output, name.getBytes(StandardCharsets.UTF_8));
        writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    private static String hash(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public enum Status {
        UNREADABLE,
        ARTIFACT_TOO_LARGE,
        MISSING_SIGNATURE_MANIFEST,
        MALFORMED_SIGNATURE_MANIFEST,
        MANIFEST_ARTIFACT_MISMATCH,
        ARTIFACT_HASH_MISMATCH,
        REVOKED_ARTIFACT,
        UNKNOWN_PUBLISHER,
        UNKNOWN_KEY,
        REVOKED_KEY,
        KEY_ALGORITHM_MISMATCH,
        INVALID_SIGNATURE,
        VERIFIED_RETIRED_KEY,
        VERIFIED_TRUSTED
    }
}
