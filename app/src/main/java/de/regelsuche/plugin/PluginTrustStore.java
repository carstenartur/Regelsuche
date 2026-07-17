package de.regelsuche.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Versioned allowlist and revocation state for external plugin publishers.
 *
 * <p>Key rotation is represented by multiple keys for one publisher. A retired
 * key remains valid for historical signatures and may name a successor;
 * revoked keys never authorize code loading.</p>
 */
public record PluginTrustStore(
    String schema,
    List<PublisherKey> keys,
    List<ArtifactRevocation> revokedArtifacts
) {
    public static final String SCHEMA = "regelsuche.plugin-trust-store/v1";

    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public PluginTrustStore {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported plugin trust-store schema");
        }
        keys = keys == null ? List.of() : keys.stream()
            .map(key -> Objects.requireNonNull(key, "publisher key"))
            .sorted(Comparator
                .comparing(PublisherKey::publisherId)
                .thenComparing(PublisherKey::keyId))
            .toList();
        revokedArtifacts = revokedArtifacts == null ? List.of() : revokedArtifacts.stream()
            .map(revocation -> Objects.requireNonNull(revocation, "artifact revocation"))
            .sorted(Comparator.comparing(ArtifactRevocation::artifactSha256))
            .toList();

        Map<String, PublisherKey> keysByIdentity = new LinkedHashMap<>();
        for (PublisherKey key : keys) {
            String identity = keyIdentity(key.publisherId(), key.keyId());
            if (keysByIdentity.putIfAbsent(identity, key) != null) {
                throw new IllegalArgumentException("duplicate publisher/key identity: "
                    + key.publisherId() + "/" + key.keyId());
            }
        }
        for (PublisherKey key : keys) {
            if (key.successorKeyId().isEmpty()) {
                continue;
            }
            PublisherKey successor = keysByIdentity.get(
                keyIdentity(key.publisherId(), key.successorKeyId()));
            if (successor == null) {
                throw new IllegalArgumentException("missing successor key: "
                    + key.publisherId() + "/" + key.successorKeyId());
            }
            if (successor.revoked()) {
                throw new IllegalArgumentException("successor key is revoked: "
                    + successor.publisherId() + "/" + successor.keyId());
            }
        }
        validateAcyclicRotation(keysByIdentity);

        Set<String> revokedHashes = new HashSet<>();
        for (ArtifactRevocation revocation : revokedArtifacts) {
            if (!revokedHashes.add(revocation.artifactSha256())) {
                throw new IllegalArgumentException("duplicate artifact revocation: "
                    + revocation.artifactSha256());
            }
        }
    }

    public static PluginTrustStore empty() {
        return new PluginTrustStore(SCHEMA, List.of(), List.of());
    }

    public static PluginTrustStore load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return empty();
        }
        try {
            TrustStoreDto dto = JSON.readValue(Files.readString(path, StandardCharsets.UTF_8), TrustStoreDto.class);
            if (dto == null) {
                throw new IllegalArgumentException("plugin trust store must not be empty");
            }
            List<PublisherKey> keys = list(dto.keys).stream()
                .map(PluginTrustStore::toPublisherKey)
                .toList();
            List<ArtifactRevocation> revocations = list(dto.revokedArtifacts).stream()
                .map(item -> new ArtifactRevocation(item.artifactSha256, item.reason))
                .toList();
            return new PluginTrustStore(dto.schema, keys, revocations);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid plugin trust store: " + path, exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to read plugin trust store: " + path, exception);
        }
    }

    public Optional<PublisherKey> findKey(String publisherId, String keyId) {
        return keys.stream()
            .filter(key -> key.publisherId().equals(publisherId) && key.keyId().equals(keyId))
            .findFirst();
    }

    public boolean hasPublisher(String publisherId) {
        return keys.stream().anyMatch(key -> key.publisherId().equals(publisherId));
    }

    public boolean isArtifactRevoked(String artifactSha256) {
        return revokedArtifacts.stream()
            .anyMatch(revocation -> revocation.artifactSha256().equals(artifactSha256));
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
            throw new IllegalStateException("Unable to write plugin trust store", exception);
        }
    }

    public String toCanonicalJson() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", schema);
        List<Map<String, Object>> keyPayloads = new ArrayList<>();
        for (PublisherKey key : keys) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("publisherId", key.publisherId());
            item.put("keyId", key.keyId());
            item.put("algorithm", key.algorithm());
            item.put("publicKeyBase64", key.publicKeyBase64());
            item.put("status", key.status().name());
            item.put("successorKeyId", key.successorKeyId());
            keyPayloads.add(item);
        }
        payload.put("keys", keyPayloads);
        List<Map<String, Object>> revocationPayloads = new ArrayList<>();
        for (ArtifactRevocation revocation : revokedArtifacts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("artifactSha256", revocation.artifactSha256());
            item.put("reason", revocation.reason());
            revocationPayloads.add(item);
        }
        payload.put("revokedArtifacts", revocationPayloads);
        try {
            return JSON.writeValueAsString(payload) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize plugin trust store", exception);
        }
    }

    private static PublisherKey toPublisherKey(PublisherKeyDto dto) {
        Objects.requireNonNull(dto, "publisher key");
        KeyStatus status;
        try {
            status = KeyStatus.valueOf(dto.status);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Unsupported publisher key status: " + dto.status, exception);
        }
        return new PublisherKey(
            dto.publisherId,
            dto.keyId,
            dto.algorithm,
            dto.publicKeyBase64,
            status,
            dto.successorKeyId
        );
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String keyIdentity(String publisherId, String keyId) {
        return publisherId + "\u0000" + keyId;
    }

    private static void validateAcyclicRotation(Map<String, PublisherKey> keysByIdentity) {
        for (PublisherKey start : keysByIdentity.values()) {
            Set<String> visited = new HashSet<>();
            PublisherKey current = start;
            while (current != null && !current.successorKeyId().isEmpty()) {
                String identity = keyIdentity(current.publisherId(), current.keyId());
                if (!visited.add(identity)) {
                    throw new IllegalArgumentException("cyclic publisher key rotation: "
                        + current.publisherId() + "/" + current.keyId());
                }
                current = keysByIdentity.get(
                    keyIdentity(current.publisherId(), current.successorKeyId()));
            }
        }
    }

    public enum KeyStatus {
        ACTIVE,
        RETIRED,
        REVOKED
    }

    public record PublisherKey(
        String publisherId,
        String keyId,
        String algorithm,
        String publicKeyBase64,
        KeyStatus status,
        String successorKeyId
    ) {
        public PublisherKey {
            publisherId = PluginSignatureManifest.requireIdentifier(publisherId, "publisherId");
            keyId = PluginSignatureManifest.requireIdentifier(keyId, "keyId");
            if (!PluginSignatureManifest.ALGORITHM.equals(algorithm)) {
                throw new IllegalArgumentException("unsupported publisher key algorithm: " + algorithm);
            }
            Objects.requireNonNull(status, "status");
            if (publicKeyBase64 == null || publicKeyBase64.isBlank()) {
                throw new IllegalArgumentException("publicKeyBase64 must not be blank");
            }
            decodePublicKey(publicKeyBase64);
            successorKeyId = successorKeyId == null ? "" : successorKeyId.trim();
            if (!successorKeyId.isEmpty()) {
                successorKeyId = PluginSignatureManifest.requireIdentifier(successorKeyId, "successorKeyId");
                if (successorKeyId.equals(keyId)) {
                    throw new IllegalArgumentException("successorKeyId must differ from keyId");
                }
                if (status != KeyStatus.RETIRED) {
                    throw new IllegalArgumentException("only retired keys may declare a successor");
                }
            }
        }

        public PublicKey publicKey() {
            return decodePublicKey(publicKeyBase64);
        }

        public boolean revoked() {
            return status == KeyStatus.REVOKED;
        }

        public boolean retired() {
            return status == KeyStatus.RETIRED;
        }
    }

    public record ArtifactRevocation(String artifactSha256, String reason) {
        public ArtifactRevocation {
            artifactSha256 = PluginSignatureManifest.requireSha256(artifactSha256, "artifactSha256");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("artifact revocation reason must not be blank");
            }
            reason = reason.trim();
        }
    }

    private static PublicKey decodePublicKey(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            return KeyFactory.getInstance(PluginSignatureManifest.ALGORITHM)
                .generatePublic(new X509EncodedKeySpec(bytes));
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new IllegalArgumentException("publicKeyBase64 is not a valid Ed25519 X.509 key", exception);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class TrustStoreDto {
        public String schema;
        public List<PublisherKeyDto> keys;
        public List<ArtifactRevocationDto> revokedArtifacts;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class PublisherKeyDto {
        public String publisherId;
        public String keyId;
        public String algorithm;
        public String publicKeyBase64;
        public String status;
        public String successorKeyId;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class ArtifactRevocationDto {
        public String artifactSha256;
        public String reason;
    }
}
