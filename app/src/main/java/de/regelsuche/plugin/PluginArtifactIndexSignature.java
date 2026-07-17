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

/**
 * Detached Ed25519 signature for one immutable plugin artifact index revision.
 *
 * <p>The signed payload binds every semantic revision identity. It does not
 * download an index, install an artifact or distribute trust state.</p>
 */
public record PluginArtifactIndexSignature(
    String schema,
    String indexId,
    String revision,
    String indexContentHash,
    String curatorId,
    String keyId,
    String algorithm,
    String signatureBase64
) {
    public static final String SCHEMA =
        "regelsuche.plugin-artifact-index-signature/v1";
    public static final String ALGORITHM = PluginSignatureManifest.ALGORITHM;

    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public PluginArtifactIndexSignature {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported plugin artifact index signature schema");
        }
        indexId = PluginSignatureManifest.requireIdentifier(indexId, "indexId");
        revision = PluginSignatureManifest.requireIdentifier(revision, "revision");
        indexContentHash = PluginSignatureManifest.requireSha256(
            indexContentHash, "indexContentHash");
        curatorId = PluginSignatureManifest.requireIdentifier(curatorId, "curatorId");
        keyId = PluginSignatureManifest.requireIdentifier(keyId, "keyId");
        if (!ALGORITHM.equals(algorithm)) {
            throw new IllegalArgumentException(
                "unsupported plugin artifact index signature algorithm: " + algorithm);
        }
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            throw new IllegalArgumentException("signatureBase64 must not be blank");
        }
        byte[] decodedSignature;
        try {
            decodedSignature = Base64.getDecoder().decode(signatureBase64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "signatureBase64 is not valid base64", exception);
        }
        if (decodedSignature.length != 64) {
            throw new IllegalArgumentException(
                "Ed25519 signatures must contain exactly 64 bytes");
        }
    }

    public static PluginArtifactIndexSignature create(
        String indexId,
        String revision,
        String indexContentHash,
        String curatorId,
        String keyId,
        String signatureBase64
    ) {
        return new PluginArtifactIndexSignature(
            SCHEMA,
            indexId,
            revision,
            indexContentHash,
            curatorId,
            keyId,
            ALGORITHM,
            signatureBase64);
    }

    public byte[] signedPayload() {
        return signedPayload(
            indexId,
            revision,
            indexContentHash,
            curatorId,
            keyId,
            algorithm);
    }

    public static byte[] signedPayload(
        String indexId,
        String revision,
        String indexContentHash,
        String curatorId,
        String keyId,
        String algorithm
    ) {
        String normalizedIndexId = PluginSignatureManifest.requireIdentifier(
            indexId, "indexId");
        String normalizedRevision = PluginSignatureManifest.requireIdentifier(
            revision, "revision");
        String normalizedIndexHash = PluginSignatureManifest.requireSha256(
            indexContentHash, "indexContentHash");
        String normalizedCurator = PluginSignatureManifest.requireIdentifier(
            curatorId, "curatorId");
        String normalizedKey = PluginSignatureManifest.requireIdentifier(keyId, "keyId");
        if (!ALGORITHM.equals(algorithm)) {
            throw new IllegalArgumentException(
                "unsupported plugin artifact index signature algorithm: " + algorithm);
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeField(output, "schema", SCHEMA);
                writeField(output, "indexId", normalizedIndexId);
                writeField(output, "revision", normalizedRevision);
                writeField(output, "indexContentHash", normalizedIndexHash);
                writeField(output, "curatorId", normalizedCurator);
                writeField(output, "keyId", normalizedKey);
                writeField(output, "algorithm", algorithm);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Unable to encode plugin artifact index signature payload", exception);
        }
    }

    public static Path sidecarFor(Path indexPath) {
        Objects.requireNonNull(indexPath, "indexPath");
        Path fileName = indexPath.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("index path must have a file name");
        }
        return indexPath.resolveSibling(fileName + ".sig.json");
    }

    public static PluginArtifactIndexSignature read(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            SignatureDto dto = JSON.readValue(
                Files.readString(path, StandardCharsets.UTF_8), SignatureDto.class);
            if (dto == null) {
                throw new IllegalArgumentException(
                    "plugin artifact index signature must not be empty");
            }
            return new PluginArtifactIndexSignature(
                dto.schema,
                dto.indexId,
                dto.revision,
                dto.indexContentHash,
                dto.curatorId,
                dto.keyId,
                dto.algorithm,
                dto.signatureBase64);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "Invalid plugin artifact index signature: " + path, exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "Unable to read plugin artifact index signature: " + path, exception);
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
            throw new IllegalStateException(
                "Unable to write plugin artifact index signature", exception);
        }
    }

    public String toCanonicalJson() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", schema);
        payload.put("indexId", indexId);
        payload.put("revision", revision);
        payload.put("indexContentHash", indexContentHash);
        payload.put("curatorId", curatorId);
        payload.put("keyId", keyId);
        payload.put("algorithm", algorithm);
        payload.put("signatureBase64", signatureBase64);
        try {
            return JSON.writeValueAsString(payload) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Unable to serialize plugin artifact index signature", exception);
        }
    }

    private static void writeField(
        DataOutputStream output,
        String name,
        String value
    ) throws IOException {
        writeBytes(output, name.getBytes(StandardCharsets.UTF_8));
        writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(DataOutputStream output, byte[] value)
            throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class SignatureDto {
        public String schema;
        public String indexId;
        public String revision;
        public String indexContentHash;
        public String curatorId;
        public String keyId;
        public String algorithm;
        public String signatureBase64;
    }
}
