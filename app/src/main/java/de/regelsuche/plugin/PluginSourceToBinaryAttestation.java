package de.regelsuche.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Signed receipt from an independently trusted builder for one publisher assertion. */
public record PluginSourceToBinaryAttestation(
    String schema,
    String provenanceContentHash,
    String artifactIdentityHash,
    String sourceSha256,
    String buildRecipeSha256,
    String rebuiltArtifactSha256,
    String builderId,
    String keyId,
    String algorithm,
    String signatureBase64
) {
    public static final String SCHEMA = "regelsuche.plugin-source-to-binary-attestation/v1";

    public PluginSourceToBinaryAttestation {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported source-to-binary attestation schema");
        }
        provenanceContentHash = PluginSignatureManifest.requireSha256(provenanceContentHash, "provenanceContentHash");
        artifactIdentityHash = PluginSignatureManifest.requireSha256(artifactIdentityHash, "artifactIdentityHash");
        sourceSha256 = PluginSignatureManifest.requireSha256(sourceSha256, "sourceSha256");
        buildRecipeSha256 = PluginSignatureManifest.requireSha256(buildRecipeSha256, "buildRecipeSha256");
        rebuiltArtifactSha256 = PluginSignatureManifest.requireSha256(rebuiltArtifactSha256, "rebuiltArtifactSha256");
        builderId = PluginSignatureManifest.requireIdentifier(builderId, "builderId");
        keyId = PluginSignatureManifest.requireIdentifier(keyId, "keyId");
        if (!PluginSignatureManifest.ALGORITHM.equals(algorithm)) {
            throw new IllegalArgumentException("unsupported source-to-binary signature algorithm");
        }
        byte[] signature = Base64.getDecoder().decode(Objects.requireNonNull(signatureBase64, "signatureBase64"));
        if (signature.length != 64 || !Base64.getEncoder().encodeToString(signature).equals(signatureBase64)) {
            throw new IllegalArgumentException("source-to-binary signature requires canonical Base64 of 64 bytes");
        }
    }

    public static PluginSourceToBinaryAttestation read(byte[] bytes) {
        JsonNode json = PluginDistributionJson.read(bytes, JsonNode.class);
        if (!json.isObject()) {
            throw new IllegalArgumentException("source-to-binary attestation must be a JSON object");
        }
        for (JsonNode value : json) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("source-to-binary attestation fields must be JSON strings");
            }
        }
        return PluginDistributionJson.read(bytes, PluginSourceToBinaryAttestation.class);
    }

    public String toCanonicalJson() {
        Map<String, String> payload = fields();
        payload.put("signatureBase64", signatureBase64);
        return PluginDistributionJson.canonical(payload);
    }

    public String contentHash() {
        return PluginDistributionJson.hash(toCanonicalJson());
    }

    /**
     * Authenticates the exact publisher assertion and independent builder receipt.
     * Supply the builder pin through local trust configuration. The caller
     * supplies the actual archived source, recipe and independent build output.
     * Verification reads these bytes without invoking build tools.
     */
    public void requireVerified(PluginArtifactIndex.Entry entry, PluginArtifactProvenance provenance,
            PluginTrustStore publisherTrustStore, PluginTrustStore.PublisherKey pinnedBuilderKey,
            byte[] sourceArchive, byte[] buildRecipe, byte[] rebuiltArtifact) {
        Objects.requireNonNull(provenance, "provenance").requireTrusted(entry, publisherTrustStore);
        if (!provenanceContentHash.equals(provenance.contentHash())
                || !artifactIdentityHash.equals(entry.identityHash())
                || !sourceSha256.equals(provenance.sourceSha256())
                || !buildRecipeSha256.equals(provenance.buildRecipeSha256())
                || !rebuiltArtifactSha256.equals(entry.artifactSha256())) {
            throw new SecurityException("attestation does not bind the selected entry and publisher assertion");
        }

        requireTrustedBuilder(publisherTrustStore, pinnedBuilderKey);
        requireHash(sourceArchive, sourceSha256, "source archive");
        requireHash(buildRecipe, buildRecipeSha256, "build recipe");
        requireHash(rebuiltArtifact, rebuiltArtifactSha256, "rebuilt artifact");
    }

    /** Each UTF-8 field name and value is preceded by a 32-bit big-endian byte count. */
    public byte[] signedPayload() {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                for (var field : fields().entrySet()) {
                    write(output, field.getKey());
                    write(output, field.getValue());
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("cannot encode source-to-binary payload", impossible);
        }
    }

    private Map<String, String> fields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schema", schema);
        fields.put("provenanceContentHash", provenanceContentHash);
        fields.put("artifactIdentityHash", artifactIdentityHash);
        fields.put("sourceSha256", sourceSha256);
        fields.put("buildRecipeSha256", buildRecipeSha256);
        fields.put("rebuiltArtifactSha256", rebuiltArtifactSha256);
        fields.put("builderId", builderId);
        fields.put("keyId", keyId);
        fields.put("algorithm", algorithm);
        return fields;
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private void requireTrustedBuilder(PluginTrustStore publisherTrustStore, PluginTrustStore.PublisherKey pin) {
        if (pin == null || pin.revoked() || !builderId.equals(pin.publisherId())
                || !keyId.equals(pin.keyId()) || !algorithm.equals(pin.algorithm())) {
            throw new SecurityException("attestation has no matching trusted builder pin");
        }
        var builderKey = pin.publicKey();
        EdECPoint builderPoint = ed25519Point(builderKey);
        if (publisherTrustStore.hasPublisher(builderId) || publisherTrustStore.keys().stream()
                .map(PluginTrustStore.PublisherKey::publicKey)
                .map(PluginSourceToBinaryAttestation::ed25519Point)
                .anyMatch(point -> point.isXOdd() == builderPoint.isXOdd()
                    && point.getY().equals(builderPoint.getY()))) {
            throw new SecurityException("builder identity and key must be independent of the publisher trust store");
        }
        try {
            var verifier = Signature.getInstance(algorithm);
            verifier.initVerify(builderKey);
            verifier.update(signedPayload());
            if (!verifier.verify(Base64.getDecoder().decode(signatureBase64))) {
                throw new SecurityException("invalid source-to-binary attestation signature");
            }
        } catch (GeneralSecurityException failure) {
            throw new SecurityException("unable to verify source-to-binary attestation", failure);
        }
    }

    private static EdECPoint ed25519Point(PublicKey key) {
        if (!(key instanceof EdECPublicKey ed25519)) {
            throw new SecurityException("Ed25519 provider did not expose the public key point");
        }
        return ed25519.getPoint();
    }

    private static void requireHash(byte[] bytes, String expected, String label) {
        if (bytes == null || !expected.equals(PluginArtifactVerifier.sha256(bytes))) {
            throw new SecurityException(label + " bytes do not match the attestation");
        }
    }
}
