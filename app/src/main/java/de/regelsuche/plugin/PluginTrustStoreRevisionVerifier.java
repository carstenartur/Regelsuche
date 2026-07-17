package de.regelsuche.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.plugin.PluginTrustStore.PublisherKey;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Verifies signed trust-store revisions against a pinned local authority store
 * and a locally persisted last-accepted checkpoint.
 */
public final class PluginTrustStoreRevisionVerifier {
    private final PluginTrustStore rootTrustStore;

    public PluginTrustStoreRevisionVerifier(PluginTrustStore rootTrustStore) {
        this.rootTrustStore = Objects.requireNonNull(
            rootTrustStore, "rootTrustStore");
    }

    public Verification verify(
        PluginTrustStore trustStore,
        PluginTrustStoreRevision revision,
        ChainCheckpoint previous
    ) {
        Objects.requireNonNull(trustStore, "trustStore");
        Objects.requireNonNull(revision, "revision");
        String storeHash = hash(trustStore.toCanonicalJson());
        String rootHash = hash(rootTrustStore.toCanonicalJson());
        String checkpointHash = previous == null ? "" : previous.contentHash();

        if (!storeHash.equals(revision.trustStoreContentHash())) {
            return result(
                revision,
                rootHash,
                checkpointHash,
                Status.TRUST_STORE_HASH_MISMATCH,
                false,
                false,
                false,
                List.of("TRUST_STORE_HASH_MISMATCH"));
        }

        Status chainFailure = validateChain(revision, previous);
        if (chainFailure != null) {
            return result(
                revision,
                rootHash,
                checkpointHash,
                chainFailure,
                false,
                false,
                false,
                List.of(chainFailure.name()));
        }

        if (!rootTrustStore.hasPublisher(revision.authorityId())) {
            return result(
                revision,
                rootHash,
                checkpointHash,
                Status.UNKNOWN_AUTHORITY,
                false,
                false,
                false,
                List.of("UNKNOWN_AUTHORITY"));
        }
        Optional<PublisherKey> keyLookup = rootTrustStore.findKey(
            revision.authorityId(), revision.keyId());
        if (keyLookup.isEmpty()) {
            return result(
                revision,
                rootHash,
                checkpointHash,
                Status.UNKNOWN_KEY,
                false,
                false,
                false,
                List.of("UNKNOWN_KEY"));
        }
        PublisherKey key = keyLookup.orElseThrow();
        if (key.revoked()) {
            return result(
                revision,
                rootHash,
                checkpointHash,
                Status.REVOKED_KEY,
                false,
                false,
                false,
                List.of("REVOKED_KEY"));
        }
        if (!revision.algorithm().equals(key.algorithm())) {
            return result(
                revision,
                rootHash,
                checkpointHash,
                Status.KEY_ALGORITHM_MISMATCH,
                false,
                false,
                false,
                List.of("KEY_ALGORITHM_MISMATCH"));
        }
        if (!verifySignature(revision, key)) {
            return result(
                revision,
                rootHash,
                checkpointHash,
                Status.INVALID_SIGNATURE,
                false,
                false,
                false,
                List.of("INVALID_SIGNATURE"));
        }

        Status status = key.retired()
            ? Status.VERIFIED_RETIRED_KEY
            : Status.VERIFIED_TRUSTED;
        List<String> warnings = key.retired()
            ? List.of("RETIRED_TRUST_AUTHORITY_KEY")
            : List.of();
        return result(
            revision,
            rootHash,
            checkpointHash,
            status,
            true,
            true,
            true,
            warnings);
    }

    public VerifiedTrustStoreRevision requireTrusted(
        PluginTrustStore trustStore,
        PluginTrustStoreRevision revision,
        ChainCheckpoint previous
    ) {
        Verification verification = verify(trustStore, revision, previous);
        if (!verification.trusted() || !verification.replaySafe()) {
            throw new SecurityException(
                "plugin trust-store revision verification failed: status="
                    + verification.status()
                    + ", trustDomainId=" + revision.trustDomainId()
                    + ", sequence=" + revision.sequence());
        }
        ChainCheckpoint checkpoint = ChainCheckpoint.create(
            revision.trustDomainId(),
            revision.sequence(),
            revision.contentHash());
        return new VerifiedTrustStoreRevision(
            trustStore,
            revision,
            verification,
            checkpoint);
    }

    private static Status validateChain(
        PluginTrustStoreRevision revision,
        ChainCheckpoint previous
    ) {
        if (previous == null) {
            return revision.sequence() == 1L
                    && revision.previousRevisionHash().isEmpty()
                ? null
                : Status.GENESIS_REQUIRED;
        }
        if (!previous.trustDomainId().equals(revision.trustDomainId())) {
            return Status.TRUST_DOMAIN_MISMATCH;
        }
        if (revision.sequence() <= previous.sequence()) {
            return Status.REPLAYED_REVISION;
        }
        if (revision.sequence() != Math.addExact(previous.sequence(), 1L)) {
            return Status.SEQUENCE_GAP;
        }
        if (!previous.revisionHash().equals(revision.previousRevisionHash())) {
            return Status.PREVIOUS_HASH_MISMATCH;
        }
        return null;
    }

    private Verification result(
        PluginTrustStoreRevision revision,
        String rootTrustStoreHash,
        String previousCheckpointHash,
        Status status,
        boolean signatureVerified,
        boolean trusted,
        boolean replaySafe,
        List<String> warnings
    ) {
        return Verification.create(
            revision.trustDomainId(),
            revision.sequence(),
            revision.contentHash(),
            revision.previousRevisionHash(),
            revision.trustStoreContentHash(),
            rootTrustStoreHash,
            previousCheckpointHash,
            status,
            signatureVerified,
            trusted,
            replaySafe,
            revision.authorityId(),
            revision.keyId(),
            warnings);
    }

    private static boolean verifySignature(
        PluginTrustStoreRevision revision,
        PublisherKey key
    ) {
        try {
            Signature verifier = Signature.getInstance(
                PluginTrustStoreRevision.ALGORITHM);
            verifier.initVerify(key.publicKey());
            verifier.update(revision.signedPayload());
            return verifier.verify(
                Base64.getDecoder().decode(revision.signatureBase64()));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }

    private static String hash(byte[] value) {
        return PluginArtifactVerifier.sha256(value);
    }

    private static String hash(String value) {
        return PluginArtifactVerifier.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public enum Status {
        TRUST_STORE_HASH_MISMATCH,
        GENESIS_REQUIRED,
        TRUST_DOMAIN_MISMATCH,
        REPLAYED_REVISION,
        SEQUENCE_GAP,
        PREVIOUS_HASH_MISMATCH,
        UNKNOWN_AUTHORITY,
        UNKNOWN_KEY,
        REVOKED_KEY,
        KEY_ALGORITHM_MISMATCH,
        INVALID_SIGNATURE,
        VERIFIED_RETIRED_KEY,
        VERIFIED_TRUSTED
    }

    /** Local last-accepted state used to reject replay, gaps and forks. */
    public record ChainCheckpoint(
        String schema,
        String trustDomainId,
        long sequence,
        String revisionHash,
        String contentHash
    ) {
        public static final String SCHEMA =
            "regelsuche.plugin-trust-store-chain-checkpoint/v1";
        private static final ObjectMapper JSON = new ObjectMapper(
            JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build())
            .findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

        public ChainCheckpoint {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported plugin trust-store chain checkpoint schema");
            }
            trustDomainId = PluginSignatureManifest.requireIdentifier(
                trustDomainId, "trustDomainId");
            if (sequence < 1L) {
                throw new IllegalArgumentException(
                    "checkpoint sequence must be positive");
            }
            revisionHash = PluginSignatureManifest.requireSha256(
                revisionHash, "revisionHash");
            contentHash = PluginSignatureManifest.requireSha256(
                contentHash, "contentHash");
            String expected = hash(render(
                trustDomainId,
                sequence,
                revisionHash,
                false,
                ""));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "plugin trust-store chain checkpoint contentHash mismatch");
            }
        }

        private static ChainCheckpoint create(
            String trustDomainId,
            long sequence,
            String revisionHash
        ) {
            String normalizedDomain = PluginSignatureManifest.requireIdentifier(
                trustDomainId, "trustDomainId");
            if (sequence < 1L) {
                throw new IllegalArgumentException(
                    "checkpoint sequence must be positive");
            }
            String normalizedRevision = PluginSignatureManifest.requireSha256(
                revisionHash, "revisionHash");
            String hash = hash(render(
                normalizedDomain,
                sequence,
                normalizedRevision,
                false,
                ""));
            return new ChainCheckpoint(
                SCHEMA,
                normalizedDomain,
                sequence,
                normalizedRevision,
                hash);
        }

        public static ChainCheckpoint read(Path path) {
            Objects.requireNonNull(path, "path");
            try {
                CheckpointDto dto = JSON.readValue(
                    Files.readString(path, StandardCharsets.UTF_8),
                    CheckpointDto.class);
                if (dto == null) {
                    throw new IllegalArgumentException(
                        "plugin trust-store checkpoint must not be empty");
                }
                if (dto.sequence == null) {
                    throw new IllegalArgumentException(
                        "checkpoint sequence must be present");
                }
                return new ChainCheckpoint(
                    dto.schema,
                    dto.trustDomainId,
                    dto.sequence,
                    dto.revisionHash,
                    dto.contentHash);
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException(
                    "Invalid plugin trust-store checkpoint: " + path, exception);
            } catch (IOException exception) {
                throw new IllegalArgumentException(
                    "Unable to read plugin trust-store checkpoint: " + path,
                    exception);
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
                    "Unable to write plugin trust-store checkpoint", exception);
            }
        }

        public String toCanonicalJson() {
            return render(
                trustDomainId,
                sequence,
                revisionHash,
                true,
                contentHash);
        }

        private static String render(
            String trustDomainId,
            long sequence,
            String revisionHash,
            boolean includeHash,
            String contentHash
        ) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schema", SCHEMA);
            payload.put("trustDomainId", trustDomainId);
            payload.put("sequence", sequence);
            payload.put("revisionHash", revisionHash);
            if (includeHash) {
                payload.put("contentHash", contentHash);
            }
            try {
                return JSON.writeValueAsString(payload) + "\n";
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException(
                    "Unable to serialize plugin trust-store checkpoint", exception);
            }
        }

        @JsonIgnoreProperties(ignoreUnknown = false)
        public static final class CheckpointDto {
            public String schema;
            public String trustDomainId;
            public Long sequence;
            public String revisionHash;
            public String contentHash;
        }
    }

    /** Immutable replayable evidence for one trust-store revision decision. */
    public record Verification(
        String schema,
        String trustDomainId,
        long sequence,
        String revisionHash,
        String previousRevisionHash,
        String trustStoreContentHash,
        String rootTrustStoreContentHash,
        String previousCheckpointHash,
        Status status,
        boolean signatureVerified,
        boolean trusted,
        boolean replaySafe,
        String authorityId,
        String keyId,
        List<String> warnings,
        String contentHash
    ) {
        public static final String SCHEMA =
            "regelsuche.plugin-trust-store-revision-verification/v1";
        private static final ObjectMapper JSON =
            new ObjectMapper().findAndRegisterModules();

        public Verification {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported trust-store revision verification schema");
            }
            trustDomainId = PluginSignatureManifest.requireIdentifier(
                trustDomainId, "trustDomainId");
            if (sequence < 1L) {
                throw new IllegalArgumentException(
                    "verification sequence must be positive");
            }
            revisionHash = PluginSignatureManifest.requireSha256(
                revisionHash, "revisionHash");
            previousRevisionHash = optionalSha256(
                previousRevisionHash, "previousRevisionHash");
            trustStoreContentHash = PluginSignatureManifest.requireSha256(
                trustStoreContentHash, "trustStoreContentHash");
            rootTrustStoreContentHash = PluginSignatureManifest.requireSha256(
                rootTrustStoreContentHash, "rootTrustStoreContentHash");
            previousCheckpointHash = optionalSha256(
                previousCheckpointHash, "previousCheckpointHash");
            Objects.requireNonNull(status, "status");
            authorityId = PluginSignatureManifest.requireIdentifier(
                authorityId, "authorityId");
            keyId = PluginSignatureManifest.requireIdentifier(keyId, "keyId");
            warnings = normalizeWarnings(warnings);
            boolean verified = status == Status.VERIFIED_TRUSTED
                || status == Status.VERIFIED_RETIRED_KEY;
            if (verified != signatureVerified
                    || verified != trusted
                    || verified != replaySafe) {
                throw new IllegalArgumentException(
                    "trust-store revision status and trust flags disagree");
            }
            contentHash = PluginSignatureManifest.requireSha256(
                contentHash, "contentHash");
            String expected = hash(canonicalMaterial(
                trustDomainId,
                sequence,
                revisionHash,
                previousRevisionHash,
                trustStoreContentHash,
                rootTrustStoreContentHash,
                previousCheckpointHash,
                status,
                signatureVerified,
                trusted,
                replaySafe,
                authorityId,
                keyId,
                warnings));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "trust-store revision verification contentHash mismatch");
            }
        }

        private static Verification create(
            String trustDomainId,
            long sequence,
            String revisionHash,
            String previousRevisionHash,
            String trustStoreContentHash,
            String rootTrustStoreContentHash,
            String previousCheckpointHash,
            Status status,
            boolean signatureVerified,
            boolean trusted,
            boolean replaySafe,
            String authorityId,
            String keyId,
            List<String> warnings
        ) {
            String normalizedDomain = PluginSignatureManifest.requireIdentifier(
                trustDomainId, "trustDomainId");
            if (sequence < 1L) {
                throw new IllegalArgumentException(
                    "verification sequence must be positive");
            }
            String normalizedRevision = PluginSignatureManifest.requireSha256(
                revisionHash, "revisionHash");
            String normalizedPrevious = optionalSha256(
                previousRevisionHash, "previousRevisionHash");
            String normalizedStore = PluginSignatureManifest.requireSha256(
                trustStoreContentHash, "trustStoreContentHash");
            String normalizedRoot = PluginSignatureManifest.requireSha256(
                rootTrustStoreContentHash, "rootTrustStoreContentHash");
            String normalizedCheckpoint = optionalSha256(
                previousCheckpointHash, "previousCheckpointHash");
            Objects.requireNonNull(status, "status");
            String normalizedAuthority = PluginSignatureManifest.requireIdentifier(
                authorityId, "authorityId");
            String normalizedKey = PluginSignatureManifest.requireIdentifier(
                keyId, "keyId");
            List<String> normalizedWarnings = normalizeWarnings(warnings);
            String hash = hash(canonicalMaterial(
                normalizedDomain,
                sequence,
                normalizedRevision,
                normalizedPrevious,
                normalizedStore,
                normalizedRoot,
                normalizedCheckpoint,
                status,
                signatureVerified,
                trusted,
                replaySafe,
                normalizedAuthority,
                normalizedKey,
                normalizedWarnings));
            return new Verification(
                SCHEMA,
                normalizedDomain,
                sequence,
                normalizedRevision,
                normalizedPrevious,
                normalizedStore,
                normalizedRoot,
                normalizedCheckpoint,
                status,
                signatureVerified,
                trusted,
                replaySafe,
                normalizedAuthority,
                normalizedKey,
                normalizedWarnings,
                hash);
        }

        public String toCanonicalJson() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schema", schema);
            payload.put("trustDomainId", trustDomainId);
            payload.put("sequence", sequence);
            payload.put("revisionHash", revisionHash);
            payload.put("previousRevisionHash", previousRevisionHash);
            payload.put("trustStoreContentHash", trustStoreContentHash);
            payload.put("rootTrustStoreContentHash", rootTrustStoreContentHash);
            payload.put("previousCheckpointHash", previousCheckpointHash);
            payload.put("status", status.name());
            payload.put("signatureVerified", signatureVerified);
            payload.put("trusted", trusted);
            payload.put("replaySafe", replaySafe);
            payload.put("authorityId", authorityId);
            payload.put("keyId", keyId);
            payload.put("warnings", warnings);
            payload.put("contentHash", contentHash);
            try {
                return JSON.writeValueAsString(payload) + "\n";
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException(
                    "Unable to serialize trust-store revision verification",
                    exception);
            }
        }

        private static byte[] canonicalMaterial(
            String trustDomainId,
            long sequence,
            String revisionHash,
            String previousRevisionHash,
            String trustStoreContentHash,
            String rootTrustStoreContentHash,
            String previousCheckpointHash,
            Status status,
            boolean signatureVerified,
            boolean trusted,
            boolean replaySafe,
            String authorityId,
            String keyId,
            List<String> warnings
        ) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream output = new DataOutputStream(bytes)) {
                    writeField(output, "schema", SCHEMA);
                    writeField(output, "trustDomainId", trustDomainId);
                    writeField(output, "sequence", Long.toString(sequence));
                    writeField(output, "revisionHash", revisionHash);
                    writeField(output, "previousRevisionHash", previousRevisionHash);
                    writeField(output, "trustStoreContentHash", trustStoreContentHash);
                    writeField(
                        output,
                        "rootTrustStoreContentHash",
                        rootTrustStoreContentHash);
                    writeField(
                        output,
                        "previousCheckpointHash",
                        previousCheckpointHash);
                    writeField(output, "status", status.name());
                    writeField(
                        output,
                        "signatureVerified",
                        Boolean.toString(signatureVerified));
                    writeField(output, "trusted", Boolean.toString(trusted));
                    writeField(output, "replaySafe", Boolean.toString(replaySafe));
                    writeField(output, "authorityId", authorityId);
                    writeField(output, "keyId", keyId);
                    output.writeInt(warnings.size());
                    for (String warning : warnings) {
                        writeField(output, "warning", warning);
                    }
                }
                return bytes.toByteArray();
            } catch (IOException exception) {
                throw new IllegalStateException(
                    "Unable to encode trust-store revision verification",
                    exception);
            }
        }
    }

    /** Authority-bearing accepted revision and the next replay checkpoint. */
    public static final class VerifiedTrustStoreRevision {
        private final PluginTrustStore trustStore;
        private final PluginTrustStoreRevision revision;
        private final Verification verification;
        private final ChainCheckpoint checkpoint;

        private VerifiedTrustStoreRevision(
            PluginTrustStore trustStore,
            PluginTrustStoreRevision revision,
            Verification verification,
            ChainCheckpoint checkpoint
        ) {
            this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
            this.revision = Objects.requireNonNull(revision, "revision");
            this.verification = Objects.requireNonNull(
                verification, "verification");
            this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            if (!verification.trusted()
                    || !verification.replaySafe()
                    || !revision.contentHash().equals(checkpoint.revisionHash())
                    || revision.sequence() != checkpoint.sequence()
                    || !revision.trustDomainId().equals(checkpoint.trustDomainId())) {
                throw new IllegalArgumentException(
                    "verified trust-store revision requires matching trusted state");
            }
        }

        public PluginTrustStore trustStore() {
            return trustStore;
        }

        public PluginTrustStoreRevision revision() {
            return revision;
        }

        public Verification verification() {
            return verification;
        }

        public ChainCheckpoint checkpoint() {
            return checkpoint;
        }
    }

    private static String optionalSha256(String value, String field) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return PluginSignatureManifest.requireSha256(value, field);
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
}
