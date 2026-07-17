package de.regelsuche.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
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
 * Signed, hash-chained distribution envelope for one plugin trust-store state.
 *
 * <p>The payload authenticates semantic trust state and its sequence position;
 * it does not fetch the revision, install artifacts or decide which checkpoint
 * a caller persists. Replay safety requires the verifier to receive the last
 * locally accepted revision state.</p>
 */
public record PluginTrustStoreRevision(
    String schema,
    String trustDomainId,
    long sequence,
    String previousRevisionHash,
    String trustStoreContentHash,
    String authorityId,
    String keyId,
    String algorithm,
    String signatureBase64,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.plugin-trust-store-revision/v1";
    public static final String ALGORITHM = PluginSignatureManifest.ALGORITHM;

    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .findAndRegisterModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public PluginTrustStoreRevision {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported plugin trust-store revision schema");
        }
        trustDomainId = PluginSignatureManifest.requireIdentifier(
            trustDomainId, "trustDomainId");
        if (sequence < 1L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        previousRevisionHash = previousHash(previousRevisionHash, sequence);
        trustStoreContentHash = PluginSignatureManifest.requireSha256(
            trustStoreContentHash, "trustStoreContentHash");
        authorityId = PluginSignatureManifest.requireIdentifier(
            authorityId, "authorityId");
        keyId = PluginSignatureManifest.requireIdentifier(keyId, "keyId");
        if (!ALGORITHM.equals(algorithm)) {
            throw new IllegalArgumentException(
                "unsupported plugin trust-store revision algorithm: " + algorithm);
        }
        signatureBase64 = canonicalSignature(signatureBase64);
        contentHash = PluginSignatureManifest.requireSha256(
            contentHash, "contentHash");
        String expected = hash(render(
            trustDomainId,
            sequence,
            previousRevisionHash,
            trustStoreContentHash,
            authorityId,
            keyId,
            algorithm,
            signatureBase64,
            false,
            ""));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "plugin trust-store revision contentHash mismatch");
        }
    }

    public static PluginTrustStoreRevision create(
        String trustDomainId,
        long sequence,
        String previousRevisionHash,
        String trustStoreContentHash,
        String authorityId,
        String keyId,
        String signatureBase64
    ) {
        String normalizedDomain = PluginSignatureManifest.requireIdentifier(
            trustDomainId, "trustDomainId");
        if (sequence < 1L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        String normalizedPrevious = previousHash(previousRevisionHash, sequence);
        String normalizedStoreHash = PluginSignatureManifest.requireSha256(
            trustStoreContentHash, "trustStoreContentHash");
        String normalizedAuthority = PluginSignatureManifest.requireIdentifier(
            authorityId, "authorityId");
        String normalizedKey = PluginSignatureManifest.requireIdentifier(
            keyId, "keyId");
        String normalizedSignature = canonicalSignature(signatureBase64);
        String hash = hash(render(
            normalizedDomain,
            sequence,
            normalizedPrevious,
            normalizedStoreHash,
            normalizedAuthority,
            normalizedKey,
            ALGORITHM,
            normalizedSignature,
            false,
            ""));
        return new PluginTrustStoreRevision(
            SCHEMA,
            normalizedDomain,
            sequence,
            normalizedPrevious,
            normalizedStoreHash,
            normalizedAuthority,
            normalizedKey,
            ALGORITHM,
            normalizedSignature,
            hash);
    }

    public byte[] signedPayload() {
        return signedPayload(
            trustDomainId,
            sequence,
            previousRevisionHash,
            trustStoreContentHash,
            authorityId,
            keyId,
            algorithm);
    }

    public static byte[] signedPayload(
        String trustDomainId,
        long sequence,
        String previousRevisionHash,
        String trustStoreContentHash,
        String authorityId,
        String keyId,
        String algorithm
    ) {
        String normalizedDomain = PluginSignatureManifest.requireIdentifier(
            trustDomainId, "trustDomainId");
        if (sequence < 1L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        String normalizedPrevious = previousHash(previousRevisionHash, sequence);
        String normalizedStoreHash = PluginSignatureManifest.requireSha256(
            trustStoreContentHash, "trustStoreContentHash");
        String normalizedAuthority = PluginSignatureManifest.requireIdentifier(
            authorityId, "authorityId");
        String normalizedKey = PluginSignatureManifest.requireIdentifier(
            keyId, "keyId");
        if (!ALGORITHM.equals(algorithm)) {
            throw new IllegalArgumentException(
                "unsupported plugin trust-store revision algorithm: " + algorithm);
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeField(output, "schema", SCHEMA);
                writeField(output, "trustDomainId", normalizedDomain);
                writeField(output, "sequence", Long.toString(sequence));
                writeField(output, "previousRevisionHash", normalizedPrevious);
                writeField(output, "trustStoreContentHash", normalizedStoreHash);
                writeField(output, "authorityId", normalizedAuthority);
                writeField(output, "keyId", normalizedKey);
                writeField(output, "algorithm", algorithm);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Unable to encode plugin trust-store revision payload", exception);
        }
    }

    public static Path sidecarFor(Path trustStorePath) {
        Objects.requireNonNull(trustStorePath, "trustStorePath");
        Path fileName = trustStorePath.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException(
                "trust-store path must have a file name");
        }
        return trustStorePath.resolveSibling(fileName + ".revision.json");
    }

    public static PluginTrustStoreRevision read(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            RevisionDto dto = JSON.readValue(
                Files.readString(path, StandardCharsets.UTF_8), RevisionDto.class);
            if (dto == null) {
                throw new IllegalArgumentException(
                    "plugin trust-store revision must not be empty");
            }
            if (dto.sequence == null) {
                throw new IllegalArgumentException("sequence must be present");
            }
            return new PluginTrustStoreRevision(
                dto.schema,
                dto.trustDomainId,
                dto.sequence,
                dto.previousRevisionHash,
                dto.trustStoreContentHash,
                dto.authorityId,
                dto.keyId,
                dto.algorithm,
                dto.signatureBase64,
                dto.contentHash);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "Invalid plugin trust-store revision: " + path, exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "Unable to read plugin trust-store revision: " + path, exception);
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
                "Unable to write plugin trust-store revision", exception);
        }
    }

    public String toCanonicalJson() {
        return render(
            trustDomainId,
            sequence,
            previousRevisionHash,
            trustStoreContentHash,
            authorityId,
            keyId,
            algorithm,
            signatureBase64,
            true,
            contentHash);
    }

    private static String render(
        String trustDomainId,
        long sequence,
        String previousRevisionHash,
        String trustStoreContentHash,
        String authorityId,
        String keyId,
        String algorithm,
        String signatureBase64,
        boolean includeHash,
        String contentHash
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", SCHEMA);
        payload.put("trustDomainId", trustDomainId);
        payload.put("sequence", sequence);
        payload.put("previousRevisionHash", previousRevisionHash);
        payload.put("trustStoreContentHash", trustStoreContentHash);
        payload.put("authorityId", authorityId);
        payload.put("keyId", keyId);
        payload.put("algorithm", algorithm);
        payload.put("signatureBase64", signatureBase64);
        if (includeHash) {
            payload.put("contentHash", contentHash);
        }
        try {
            return JSON.writeValueAsString(payload) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Unable to serialize plugin trust-store revision", exception);
        }
    }

    private static String previousHash(String value, long sequence) {
        String normalized = value == null ? "" : value;
        if (sequence == 1L) {
            if (!normalized.isEmpty()) {
                throw new IllegalArgumentException(
                    "genesis revision must not name a previous revision");
            }
            return "";
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                "non-genesis revision requires previousRevisionHash");
        }
        return PluginSignatureManifest.requireSha256(
            normalized, "previousRevisionHash");
    }

    private static String canonicalSignature(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("signatureBase64 must not be blank");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "signatureBase64 is not valid base64", exception);
        }
        if (decoded.length != 64) {
            throw new IllegalArgumentException(
                "Ed25519 signatures must contain exactly 64 bytes");
        }
        String canonical = Base64.getEncoder().encodeToString(decoded);
        if (!canonical.equals(value)) {
            throw new IllegalArgumentException(
                "signatureBase64 must use canonical padded base64");
        }
        return canonical;
    }

    private static String hash(String value) {
        return PluginArtifactVerifier.sha256(value.getBytes(StandardCharsets.UTF_8));
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
    public static final class RevisionDto {
        public String schema;
        public String trustDomainId;
        public Long sequence;
        public String previousRevisionHash;
        public String trustStoreContentHash;
        public String authorityId;
        public String keyId;
        public String algorithm;
        public String signatureBase64;
        public String contentHash;
    }
}
