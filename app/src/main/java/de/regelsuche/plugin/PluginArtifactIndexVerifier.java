package de.regelsuche.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.plugin.PluginTrustStore.PublisherKey;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

/** Verifies immutable artifact-index revisions against trusted Ed25519 keys. */
public final class PluginArtifactIndexVerifier {
    private final PluginTrustStore trustStore;

    public PluginArtifactIndexVerifier(PluginTrustStore trustStore) {
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
    }

    public Verification verify(
        PluginArtifactIndex index,
        PluginArtifactIndexSignature signature
    ) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(signature, "signature");
        String signatureHash = PluginArtifactVerifier.sha256(
            signature.toCanonicalJson().getBytes(StandardCharsets.UTF_8));
        String trustStoreHash = PluginArtifactVerifier.sha256(
            trustStore.toCanonicalJson().getBytes(StandardCharsets.UTF_8));

        if (!index.indexId().equals(signature.indexId())) {
            return result(
                index,
                signature,
                signatureHash,
                trustStoreHash,
                Status.INDEX_ID_MISMATCH,
                false,
                false,
                List.of("INDEX_ID_MISMATCH"));
        }
        if (!index.revision().equals(signature.revision())) {
            return result(
                index,
                signature,
                signatureHash,
                trustStoreHash,
                Status.REVISION_MISMATCH,
                false,
                false,
                List.of("REVISION_MISMATCH"));
        }
        if (!index.contentHash().equals(signature.indexContentHash())) {
            return result(
                index,
                signature,
                signatureHash,
                trustStoreHash,
                Status.INDEX_HASH_MISMATCH,
                false,
                false,
                List.of("INDEX_HASH_MISMATCH"));
        }
        if (!index.curatorId().equals(signature.curatorId())) {
            return result(
                index,
                signature,
                signatureHash,
                trustStoreHash,
                Status.CURATOR_MISMATCH,
                false,
                false,
                List.of("CURATOR_MISMATCH"));
        }
        if (!trustStore.hasPublisher(signature.curatorId())) {
            return result(
                index,
                signature,
                signatureHash,
                trustStoreHash,
                Status.UNKNOWN_CURATOR,
                false,
                false,
                List.of("UNKNOWN_CURATOR"));
        }
        Optional<PublisherKey> keyLookup = trustStore.findKey(
            signature.curatorId(), signature.keyId());
        if (keyLookup.isEmpty()) {
            return result(
                index,
                signature,
                signatureHash,
                trustStoreHash,
                Status.UNKNOWN_KEY,
                false,
                false,
                List.of("UNKNOWN_KEY"));
        }
        PublisherKey key = keyLookup.orElseThrow();
        if (key.revoked()) {
            return result(
                index,
                signature,
                signatureHash,
                trustStoreHash,
                Status.REVOKED_KEY,
                false,
                false,
                List.of("REVOKED_KEY"));
        }
        if (!signature.algorithm().equals(key.algorithm())) {
            return result(
                index,
                signature,
                signatureHash,
                trustStoreHash,
                Status.KEY_ALGORITHM_MISMATCH,
                false,
                false,
                List.of("KEY_ALGORITHM_MISMATCH"));
        }
        if (!verifySignature(signature, key)) {
            return result(
                index,
                signature,
                signatureHash,
                trustStoreHash,
                Status.INVALID_SIGNATURE,
                false,
                false,
                List.of("INVALID_SIGNATURE"));
        }

        Status status = key.retired()
            ? Status.VERIFIED_RETIRED_KEY
            : Status.VERIFIED_TRUSTED;
        List<String> warnings = key.retired()
            ? List.of("RETIRED_CURATOR_KEY")
            : List.of();
        return result(
            index,
            signature,
            signatureHash,
            trustStoreHash,
            status,
            true,
            true,
            warnings);
    }

    public VerifiedIndex requireTrusted(Path indexPath) {
        Path index = Objects.requireNonNull(indexPath, "indexPath");
        return requireTrusted(index, PluginArtifactIndexSignature.sidecarFor(index));
    }

    public VerifiedIndex requireTrusted(Path indexPath, Path signaturePath) {
        Path indexFile = Objects.requireNonNull(indexPath, "indexPath");
        Path signatureFile = Objects.requireNonNull(signaturePath, "signaturePath");
        PluginArtifactIndex index = PluginArtifactIndex.load(indexFile);
        PluginArtifactIndexSignature signature =
            PluginArtifactIndexSignature.read(signatureFile);
        Verification verification = verify(index, signature);
        if (!verification.trusted()) {
            throw new SecurityException(
                "plugin artifact index verification failed: status="
                    + verification.status()
                    + ", indexPath=" + indexFile.toAbsolutePath().normalize()
                    + ", signaturePath="
                    + signatureFile.toAbsolutePath().normalize());
        }
        return new VerifiedIndex(index, verification);
    }

    private Verification result(
        PluginArtifactIndex index,
        PluginArtifactIndexSignature signature,
        String signatureHash,
        String trustStoreHash,
        Status status,
        boolean signatureVerified,
        boolean trusted,
        List<String> warnings
    ) {
        return Verification.create(
            index.indexId(),
            index.revision(),
            index.contentHash(),
            signatureHash,
            trustStoreHash,
            status,
            signatureVerified,
            trusted,
            signature.curatorId(),
            signature.keyId(),
            warnings);
    }

    private static boolean verifySignature(
        PluginArtifactIndexSignature manifest,
        PublisherKey key
    ) {
        try {
            Signature verifier = Signature.getInstance(
                PluginArtifactIndexSignature.ALGORITHM);
            verifier.initVerify(key.publicKey());
            verifier.update(manifest.signedPayload());
            return verifier.verify(
                Base64.getDecoder().decode(manifest.signatureBase64()));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }

    public enum Status {
        INDEX_ID_MISMATCH,
        REVISION_MISMATCH,
        INDEX_HASH_MISMATCH,
        CURATOR_MISMATCH,
        UNKNOWN_CURATOR,
        UNKNOWN_KEY,
        REVOKED_KEY,
        KEY_ALGORITHM_MISMATCH,
        INVALID_SIGNATURE,
        VERIFIED_RETIRED_KEY,
        VERIFIED_TRUSTED
    }

    /** Immutable, replayable evidence for one index-signature decision. */
    public record Verification(
        String schema,
        String indexId,
        String revision,
        String indexContentHash,
        String signatureContentHash,
        String trustStoreContentHash,
        Status status,
        boolean signatureVerified,
        boolean trusted,
        String curatorId,
        String keyId,
        List<String> warnings,
        String contentHash
    ) {
        public static final String SCHEMA =
            "regelsuche.plugin-artifact-index-verification/v1";
        private static final ObjectMapper JSON =
            new ObjectMapper().findAndRegisterModules();

        public Verification {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported plugin artifact index verification schema");
            }
            indexId = PluginSignatureManifest.requireIdentifier(indexId, "indexId");
            revision = PluginSignatureManifest.requireIdentifier(revision, "revision");
            indexContentHash = PluginSignatureManifest.requireSha256(
                indexContentHash, "indexContentHash");
            signatureContentHash = PluginSignatureManifest.requireSha256(
                signatureContentHash, "signatureContentHash");
            trustStoreContentHash = PluginSignatureManifest.requireSha256(
                trustStoreContentHash, "trustStoreContentHash");
            Objects.requireNonNull(status, "status");
            curatorId = PluginSignatureManifest.requireIdentifier(
                curatorId, "curatorId");
            keyId = PluginSignatureManifest.requireIdentifier(keyId, "keyId");
            warnings = normalizeWarnings(warnings);
            boolean verifiedStatus = status == Status.VERIFIED_TRUSTED
                || status == Status.VERIFIED_RETIRED_KEY;
            if (verifiedStatus != signatureVerified || verifiedStatus != trusted) {
                throw new IllegalArgumentException(
                    "index verification status and trust flags disagree");
            }
            String expected = PluginArtifactVerifier.sha256(canonicalMaterial(
                indexId,
                revision,
                indexContentHash,
                signatureContentHash,
                trustStoreContentHash,
                status,
                signatureVerified,
                trusted,
                curatorId,
                keyId,
                warnings));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "plugin artifact index verification contentHash mismatch");
            }
        }

        private static Verification create(
            String indexId,
            String revision,
            String indexContentHash,
            String signatureContentHash,
            String trustStoreContentHash,
            Status status,
            boolean signatureVerified,
            boolean trusted,
            String curatorId,
            String keyId,
            List<String> warnings
        ) {
            String normalizedIndex = PluginSignatureManifest.requireIdentifier(
                indexId, "indexId");
            String normalizedRevision = PluginSignatureManifest.requireIdentifier(
                revision, "revision");
            String normalizedIndexHash = PluginSignatureManifest.requireSha256(
                indexContentHash, "indexContentHash");
            String normalizedSignatureHash = PluginSignatureManifest.requireSha256(
                signatureContentHash, "signatureContentHash");
            String normalizedTrustStoreHash = PluginSignatureManifest.requireSha256(
                trustStoreContentHash, "trustStoreContentHash");
            Objects.requireNonNull(status, "status");
            String normalizedCurator = PluginSignatureManifest.requireIdentifier(
                curatorId, "curatorId");
            String normalizedKey = PluginSignatureManifest.requireIdentifier(
                keyId, "keyId");
            List<String> normalizedWarnings = normalizeWarnings(warnings);
            String hash = PluginArtifactVerifier.sha256(canonicalMaterial(
                normalizedIndex,
                normalizedRevision,
                normalizedIndexHash,
                normalizedSignatureHash,
                normalizedTrustStoreHash,
                status,
                signatureVerified,
                trusted,
                normalizedCurator,
                normalizedKey,
                normalizedWarnings));
            return new Verification(
                SCHEMA,
                normalizedIndex,
                normalizedRevision,
                normalizedIndexHash,
                normalizedSignatureHash,
                normalizedTrustStoreHash,
                status,
                signatureVerified,
                trusted,
                normalizedCurator,
                normalizedKey,
                normalizedWarnings,
                hash);
        }

        public String toCanonicalJson() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schema", schema);
            payload.put("indexId", indexId);
            payload.put("revision", revision);
            payload.put("indexContentHash", indexContentHash);
            payload.put("signatureContentHash", signatureContentHash);
            payload.put("trustStoreContentHash", trustStoreContentHash);
            payload.put("status", status.name());
            payload.put("signatureVerified", signatureVerified);
            payload.put("trusted", trusted);
            payload.put("curatorId", curatorId);
            payload.put("keyId", keyId);
            payload.put("warnings", warnings);
            payload.put("contentHash", contentHash);
            try {
                return JSON.writeValueAsString(payload) + "\n";
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException(
                    "Unable to serialize plugin artifact index verification",
                    exception);
            }
        }

        private static byte[] canonicalMaterial(
            String indexId,
            String revision,
            String indexContentHash,
            String signatureContentHash,
            String trustStoreContentHash,
            Status status,
            boolean signatureVerified,
            boolean trusted,
            String curatorId,
            String keyId,
            List<String> warnings
        ) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream output = new DataOutputStream(bytes)) {
                    writeField(output, "schema", SCHEMA);
                    writeField(output, "indexId", indexId);
                    writeField(output, "revision", revision);
                    writeField(output, "indexContentHash", indexContentHash);
                    writeField(output, "signatureContentHash", signatureContentHash);
                    writeField(output, "trustStoreContentHash", trustStoreContentHash);
                    writeField(output, "status", status.name());
                    writeField(
                        output,
                        "signatureVerified",
                        Boolean.toString(signatureVerified));
                    writeField(output, "trusted", Boolean.toString(trusted));
                    writeField(output, "curatorId", curatorId);
                    writeField(output, "keyId", keyId);
                    output.writeInt(warnings.size());
                    for (String warning : warnings) {
                        writeField(output, "warning", warning);
                    }
                }
                return bytes.toByteArray();
            } catch (IOException exception) {
                throw new IllegalStateException(
                    "Unable to encode plugin artifact index verification",
                    exception);
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

    /**
     * Authority-bearing result that can only be created by this verifier after
     * a successful trust decision.
     */
    public static final class VerifiedIndex {
        private final PluginArtifactIndex index;
        private final Verification verification;

        private VerifiedIndex(
            PluginArtifactIndex index,
            Verification verification
        ) {
            this.index = Objects.requireNonNull(index, "index");
            this.verification = Objects.requireNonNull(
                verification, "verification");
            if (!verification.trusted()
                    || !index.indexId().equals(verification.indexId())
                    || !index.revision().equals(verification.revision())
                    || !index.contentHash().equals(verification.indexContentHash())
                    || !index.curatorId().equals(verification.curatorId())) {
                throw new IllegalArgumentException(
                    "verified index requires trusted matching verification evidence");
            }
        }

        public PluginArtifactIndex index() {
            return index;
        }

        public Verification verification() {
            return verification;
        }
    }
}
