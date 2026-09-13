package de.regelsuche.plugin;

import de.regelsuche.plugin.PluginTrustStoreRevisionVerifier.ChainCheckpoint;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Canonical retained package-state evidence. Only the external authority selects an active instance. */
public record PluginInstallationEvidence(
    String schema,
    String operation,
    String previousInstallationHash,
    String rollbackSourceHash,
    ChainCheckpoint checkpoint,
    String rootTrustStoreHash,
    String trustStoreHash,
    String indexId,
    String indexRevision,
    String indexContentHash,
    String resolutionContentHash,
    List<Artifact> artifacts,
    Map<String, String> files,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.plugin-installation/v1";

    public PluginInstallationEvidence {
        if (!SCHEMA.equals(schema) || !Set.of("INSTALL", "UPDATE", "REMOVE", "ROLLBACK").contains(operation)) {
            throw new IllegalArgumentException("unsupported installation schema or operation");
        }
        previousInstallationHash = optionalHash(previousInstallationHash);
        rollbackSourceHash = optionalHash(rollbackSourceHash);
        if (operation.equals("ROLLBACK") == rollbackSourceHash.isEmpty()) {
            throw new IllegalArgumentException("only rollback requires a retained source generation");
        }
        Objects.requireNonNull(checkpoint, "checkpoint");
        PluginSignatureManifest.requireSha256(rootTrustStoreHash, "rootTrustStoreHash");
        PluginSignatureManifest.requireSha256(trustStoreHash, "trustStoreHash");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        if (artifacts.isEmpty()) {
            if (!"".equals(indexId) || !"".equals(indexRevision) || !"".equals(indexContentHash)
                    || !"".equals(resolutionContentHash)) {
                throw new IllegalArgumentException("empty installation cannot claim an index or resolution");
            }
        } else {
            PluginSignatureManifest.requireIdentifier(indexId, "indexId");
            PluginSignatureManifest.requireIdentifier(indexRevision, "indexRevision");
            PluginSignatureManifest.requireSha256(indexContentHash, "indexContentHash");
            PluginSignatureManifest.requireSha256(resolutionContentHash, "resolutionContentHash");
        }
        files = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(files, "files")));
        files.forEach((path, hash) -> {
            requirePath(path);
            PluginSignatureManifest.requireSha256(hash, "file hash");
        });
        Set<String> identities = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (Artifact artifact : artifacts) {
            if (!identities.add(artifact.identityHash()) || !paths.add(artifact.path())
                    || !artifact.artifactSha256().equals(files.get(artifact.path()))) {
                throw new IllegalArgumentException("installation artifacts must bind distinct retained files");
            }
        }
        if (!PluginDistributionJson.hash(PluginDistributionJson.canonical(payload(operation,
                previousInstallationHash, rollbackSourceHash, checkpoint, rootTrustStoreHash, trustStoreHash,
                indexId, indexRevision, indexContentHash, resolutionContentHash, artifacts, files))).equals(contentHash)) {
            throw new IllegalArgumentException("installation evidence contentHash mismatch");
        }
    }

    static PluginInstallationEvidence create(String operation, String previous, String rollbackSource,
            ChainCheckpoint checkpoint, String rootHash, String trustHash, String indexId, String indexRevision,
            String indexHash, String resolutionHash, List<Artifact> artifacts, Map<String, String> files) {
        String hash = PluginDistributionJson.hash(PluginDistributionJson.canonical(payload(operation,
            previous, rollbackSource, checkpoint, rootHash, trustHash, indexId, indexRevision, indexHash,
            resolutionHash, artifacts, new TreeMap<>(files))));
        return new PluginInstallationEvidence(SCHEMA, operation, previous, rollbackSource, checkpoint,
            rootHash, trustHash, indexId, indexRevision, indexHash, resolutionHash, artifacts, files, hash);
    }

    public String toCanonicalJson() {
        Map<String, Object> payload = payload(operation, previousInstallationHash, rollbackSourceHash,
            checkpoint, rootTrustStoreHash, trustStoreHash, indexId, indexRevision, indexContentHash,
            resolutionContentHash, artifacts, files);
        payload.put("contentHash", contentHash);
        return PluginDistributionJson.canonical(payload);
    }

    public static PluginInstallationEvidence read(byte[] bytes) {
        return PluginDistributionJson.read(bytes, PluginInstallationEvidence.class);
    }

    private static Map<String, Object> payload(String operation, String previous, String rollbackSource,
            ChainCheckpoint checkpoint, String rootHash, String trustHash, String indexId, String indexRevision,
            String indexHash, String resolutionHash, List<Artifact> artifacts, Map<String, String> files) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", SCHEMA);
        payload.put("operation", operation);
        payload.put("previousInstallationHash", previous);
        payload.put("rollbackSourceHash", rollbackSource);
        payload.put("checkpoint", checkpoint);
        payload.put("rootTrustStoreHash", rootHash);
        payload.put("trustStoreHash", trustHash);
        payload.put("indexId", indexId);
        payload.put("indexRevision", indexRevision);
        payload.put("indexContentHash", indexHash);
        payload.put("resolutionContentHash", resolutionHash);
        payload.put("artifacts", artifacts);
        payload.put("files", files);
        return payload;
    }

    static String requirePath(String path) {
        if (path == null || !path.matches("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*")) {
            throw new IllegalArgumentException("installation requires portable relative paths");
        }
        for (String part : path.split("/")) {
            if (part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("installation paths cannot traverse directories");
            }
        }
        return path;
    }

    private static String optionalHash(String hash) {
        if (hash == null) {
            throw new IllegalArgumentException("optional hash must be present");
        }
        return hash.isEmpty() ? "" : PluginSignatureManifest.requireSha256(hash, "hash");
    }

    public record Artifact(String kind, String componentId, String version, String identityHash,
        String artifactSha256, String publisherId, String path, String provenanceContentHash,
        String verificationContentHash) {
        public Artifact {
            PluginArtifactIndex.ArtifactKind.valueOf(kind);
            PluginSignatureManifest.requireIdentifier(componentId, "componentId");
            PluginArtifactIndex.requireVersion(version, "version");
            PluginSignatureManifest.requireSha256(identityHash, "identityHash");
            PluginSignatureManifest.requireSha256(artifactSha256, "artifactSha256");
            PluginSignatureManifest.requireIdentifier(publisherId, "publisherId");
            requirePath(path);
            PluginSignatureManifest.requireSha256(provenanceContentHash, "provenanceContentHash");
            PluginSignatureManifest.requireSha256(verificationContentHash, "verificationContentHash");
        }
    }
}
