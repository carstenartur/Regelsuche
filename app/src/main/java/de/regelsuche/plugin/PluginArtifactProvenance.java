package de.regelsuche.plugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A publisher's signed source/build assertion, bound to one exact index entry.
 * Verification authenticates this assertion; it does not independently rebuild
 * the artifact or attest that the claimed source produced those bytes.
 */
public record PluginArtifactProvenance(
    String schema,
    String artifactIdentityHash,
    String artifactSha256,
    String provenanceUri,
    String sourceUri,
    String sourceRevision,
    String sourceSha256,
    String buildRecipeSha256,
    String publisherId,
    String keyId,
    String algorithm,
    String signatureBase64
) {
    public static final String SCHEMA = "regelsuche.plugin-artifact-provenance/v1";

    public PluginArtifactProvenance {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported artifact provenance schema");
        }
        artifactIdentityHash = PluginSignatureManifest.requireSha256(artifactIdentityHash, "artifactIdentityHash");
        artifactSha256 = PluginSignatureManifest.requireSha256(artifactSha256, "artifactSha256");
        provenanceUri = https(provenanceUri);
        sourceUri = https(sourceUri);
        sourceRevision = PluginSignatureManifest.requireIdentifier(sourceRevision, "sourceRevision");
        sourceSha256 = PluginSignatureManifest.requireSha256(sourceSha256, "sourceSha256");
        buildRecipeSha256 = PluginSignatureManifest.requireSha256(buildRecipeSha256, "buildRecipeSha256");
        publisherId = PluginSignatureManifest.requireIdentifier(publisherId, "publisherId");
        keyId = PluginSignatureManifest.requireIdentifier(keyId, "keyId");
        if (!"Ed25519".equals(algorithm)) {
            throw new IllegalArgumentException("unsupported provenance signature algorithm");
        }
        byte[] signature = Base64.getDecoder().decode(Objects.requireNonNull(signatureBase64, "signatureBase64"));
        if (signature.length != 64 || !Base64.getEncoder().encodeToString(signature).equals(signatureBase64)) {
            throw new IllegalArgumentException("provenance signature requires canonical Base64 of 64 bytes");
        }
    }

    public static PluginArtifactProvenance read(byte[] bytes) {
        return PluginDistributionJson.read(bytes, PluginArtifactProvenance.class);
    }

    public String toCanonicalJson() {
        Map<String, String> payload = fields();
        payload.put("signatureBase64", signatureBase64);
        return PluginDistributionJson.canonical(payload);
    }

    public String contentHash() {
        return PluginDistributionJson.hash(toCanonicalJson());
    }

    /** Existing signed working trust state is the only source of publisher keys. */
    public void requireTrusted(PluginArtifactIndex.Entry entry, PluginTrustStore trustStore) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(trustStore, "trustStore");
        if (!artifactIdentityHash.equals(entry.identityHash())
                || !artifactSha256.equals(entry.artifactSha256())
                || !publisherId.equals(entry.publisherId())
                || !provenanceUri.equals(entry.provenanceUri())) {
            throw new SecurityException("provenance does not bind the selected index entry");
        }
        var key = trustStore.findKey(publisherId, keyId)
            .orElseThrow(() -> new SecurityException("unknown provenance publisher/key"));
        if (key.revoked() || trustStore.isArtifactRevoked(artifactSha256)
                || !algorithm.equals(key.algorithm())) {
            throw new SecurityException("provenance is revoked or uses an untrusted algorithm");
        }
        try {
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(key.publicKey());
            verifier.update(signedPayload());
            if (!verifier.verify(Base64.getDecoder().decode(signatureBase64))) {
                throw new SecurityException("invalid provenance signature");
            }
        } catch (GeneralSecurityException failure) {
            throw new SecurityException("unable to verify provenance", failure);
        }
    }

    /** Each UTF-8 field name and value is preceded by a 32-bit big-endian byte count. */
    public byte[] signedPayload() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                for (var field : fields().entrySet()) {
                    write(output, field.getKey());
                    write(output, field.getValue());
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("cannot encode provenance payload", impossible);
        }
    }

    private Map<String, String> fields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schema", schema);
        fields.put("artifactIdentityHash", artifactIdentityHash);
        fields.put("artifactSha256", artifactSha256);
        fields.put("provenanceUri", provenanceUri);
        fields.put("sourceUri", sourceUri);
        fields.put("sourceRevision", sourceRevision);
        fields.put("sourceSha256", sourceSha256);
        fields.put("buildRecipeSha256", buildRecipeSha256);
        fields.put("publisherId", publisherId);
        fields.put("keyId", keyId);
        fields.put("algorithm", algorithm);
        return fields;
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String https(String value) {
        URI uri = URI.create(Objects.requireNonNull(value, "URI"));
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535 || !uri.normalize().equals(uri)) {
            throw new IllegalArgumentException("provenance requires a normalized HTTPS URI");
        }
        return value;
    }
}
