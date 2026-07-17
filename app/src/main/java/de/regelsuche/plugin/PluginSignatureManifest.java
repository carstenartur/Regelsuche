package de.regelsuche.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Strict detached signature envelope for one external plugin JAR.
 *
 * <p>The Ed25519 signature covers an unambiguous, length-prefixed encoding of
 * every identity field except the signature itself. The manifest is stored next
 * to the artifact as {@code <artifact>.sig.json}.</p>
 */
public record PluginSignatureManifest(
    String schema,
    String artifactFileName,
    String artifactSha256,
    String publisherId,
    String keyId,
    String algorithm,
    String signatureBase64
) {
    public static final String SCHEMA = "regelsuche.plugin-signature/v1";
    public static final String ALGORITHM = "Ed25519";

    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public PluginSignatureManifest {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported plugin signature schema");
        }
        artifactFileName = requireFileName(artifactFileName);
        artifactSha256 = requireSha256(artifactSha256, "artifactSha256");
        publisherId = requireIdentifier(publisherId, "publisherId");
        keyId = requireIdentifier(keyId, "keyId");
        if (!ALGORITHM.equals(algorithm)) {
            throw new IllegalArgumentException("unsupported plugin signature algorithm: " + algorithm);
        }
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            throw new IllegalArgumentException("signatureBase64 must not be blank");
        }
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(signatureBase64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("signatureBase64 is not valid base64", exception);
        }
        if (signature.length != 64) {
            throw new IllegalArgumentException("Ed25519 signatures must contain exactly 64 bytes");
        }
    }

    public static PluginSignatureManifest create(
        String artifactFileName,
        String artifactSha256,
        String publisherId,
        String keyId,
        String signatureBase64
    ) {
        return new PluginSignatureManifest(
            SCHEMA,
            artifactFileName,
            artifactSha256,
            publisherId,
            keyId,
            ALGORITHM,
            signatureBase64
        );
    }

    public byte[] signedPayload() {
        return signedPayload(
            artifactFileName,
            artifactSha256,
            publisherId,
            keyId,
            algorithm
        );
    }

    public static byte[] signedPayload(
        String artifactFileName,
        String artifactSha256,
        String publisherId,
        String keyId,
        String algorithm
    ) {
        String normalizedArtifact = requireFileName(artifactFileName);
        String normalizedHash = requireSha256(artifactSha256, "artifactSha256");
        String normalizedPublisher = requireIdentifier(publisherId, "publisherId");
        String normalizedKey = requireIdentifier(keyId, "keyId");
        if (!ALGORITHM.equals(algorithm)) {
            throw new IllegalArgumentException("unsupported plugin signature algorithm: " + algorithm);
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeField(output, "schema", SCHEMA);
                writeField(output, "artifactFileName", normalizedArtifact);
                writeField(output, "artifactSha256", normalizedHash);
                writeField(output, "publisherId", normalizedPublisher);
                writeField(output, "keyId", normalizedKey);
                writeField(output, "algorithm", algorithm);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode plugin signature payload", exception);
        }
    }

    public static Path sidecarFor(Path artifact) {
        Objects.requireNonNull(artifact, "artifact");
        Path fileName = artifact.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("artifact must have a file name");
        }
        return artifact.resolveSibling(fileName + ".sig.json");
    }

    public static PluginSignatureManifest read(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            ManifestDto dto = JSON.readValue(Files.readString(path, StandardCharsets.UTF_8), ManifestDto.class);
            if (dto == null) {
                throw new IllegalArgumentException("plugin signature manifest must not be empty");
            }
            return new PluginSignatureManifest(
                dto.schema,
                dto.artifactFileName,
                dto.artifactSha256,
                dto.publisherId,
                dto.keyId,
                dto.algorithm,
                dto.signatureBase64
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid plugin signature manifest: " + path, exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to read plugin signature manifest: " + path, exception);
        }
    }

    public Path write(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            Path parent = path.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, toCanonicalJson(), StandardCharsets.UTF_8);
            return path;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write plugin signature manifest", exception);
        }
    }

    public String toCanonicalJson() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", schema);
        payload.put("artifactFileName", artifactFileName);
        payload.put("artifactSha256", artifactSha256);
        payload.put("publisherId", publisherId);
        payload.put("keyId", keyId);
        payload.put("algorithm", algorithm);
        payload.put("signatureBase64", signatureBase64);
        try {
            return JSON.writeValueAsString(payload) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize plugin signature manifest", exception);
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

    private static String requireFileName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("artifactFileName must not be blank");
        }
        if (!Path.of(value).getFileName().toString().equals(value)
                || value.contains("/") || value.contains("\\")) {
            throw new IllegalArgumentException("artifactFileName must be a simple file name");
        }
        return value;
    }

    static String requireSha256(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must use sha256:<64 lowercase hex characters>");
        }
        return value;
    }

    static String requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a valid identifier");
        }
        return value;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class ManifestDto {
        public String schema;
        public String artifactFileName;
        public String artifactSha256;
        public String publisherId;
        public String keyId;
        public String algorithm;
        public String signatureBase64;
    }
}
