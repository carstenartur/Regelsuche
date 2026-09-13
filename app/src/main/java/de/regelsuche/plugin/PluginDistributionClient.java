package de.regelsuche.plugin;

import de.regelsuche.plugin.PluginArtifactIndex.ArtifactKind;
import de.regelsuche.plugin.PluginArtifactIndex.Entry;
import de.regelsuche.plugin.PluginArtifactIndexVerifier.VerifiedIndex;
import de.regelsuche.plugin.PluginArtifactResolver.ResolutionReceipt;
import de.regelsuche.plugin.PluginArtifactResolver.ResolutionRequest;
import de.regelsuche.plugin.PluginCheckpointAuthority.AcceptedState;
import de.regelsuche.plugin.PluginInstallationStore.Snapshot;
import de.regelsuche.plugin.PluginTrustStoreRevisionVerifier.VerifiedTrustStoreRevision;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Authenticated network installation of one managed Java-plugin dependency closure.
 * It stores packages; it never loads classes, runs package hooks or imports rules.
 * Every active read consults the external authority and verifies retained file hashes.
 */
public final class PluginDistributionClient {
    private static final List<String> TRUST_FILES = List.of("trust-store.json", "trust-revision.json",
        "trust-verification.json", "checkpoint.json");
    private final String trustDomain;
    private final PluginTrustStore roots;
    private final String rootHash;
    private final PluginCheckpointAuthority authority;
    private final PluginDistributionTransport transport;
    private final Limits limits;
    private final PluginInstallationStore store;

    public PluginDistributionClient(Path directory, String trustDomain, PluginTrustStore pinnedRoots,
            PluginCheckpointAuthority authority, PluginDistributionTransport transport, Limits limits) throws IOException {
        this.trustDomain = PluginSignatureManifest.requireIdentifier(trustDomain, "trustDomain");
        this.roots = Objects.requireNonNull(pinnedRoots, "pinnedRoots");
        this.rootHash = PluginDistributionJson.hash(roots.toCanonicalJson());
        this.authority = Objects.requireNonNull(authority, "external checkpoint authority is required");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.store = new PluginInstallationStore(Objects.requireNonNull(directory, "directory"), limits);
    }

    /**
     * Installs/replaces the entire closure. Each call must receive the immediate
     * successor trust revision (genesis for a new authority), never a replay.
     */
    public PluginInstallationEvidence install(Sources sources, ResolutionRequest request) throws IOException {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(request, "request");
        AcceptedState previous = accepted();
        Snapshot current = active(previous);
        Budget budget = new Budget();
        try (var work = store.work()) {
            byte[] trustBytes = budget.json(sources.trustStoreUri());
            byte[] revisionBytes = budget.json(sources.trustRevisionUri());
            PluginInstallationStore.write(work.directory(), "trust-store.json", trustBytes);
            PluginInstallationStore.write(work.directory(), "trust-revision.json", revisionBytes);
            PluginTrustStore trust = PluginTrustStore.load(work.directory().resolve("trust-store.json"));
            PluginTrustStoreRevision revision = PluginTrustStoreRevision.read(work.directory().resolve("trust-revision.json"));
            if (!trustDomain.equals(revision.trustDomainId())) {
                throw new SecurityException("unexpected distribution trust domain");
            }
            VerifiedTrustStoreRevision verifiedTrust = new PluginTrustStoreRevisionVerifier(roots)
                .requireTrusted(trust, revision, previous.checkpoint());
            byte[] indexBytes = budget.json(sources.indexUri());
            byte[] signatureBytes = budget.json(sources.indexSignatureUri());
            PluginInstallationStore.write(work.directory(), "index.json", indexBytes);
            PluginInstallationStore.write(work.directory(), "index.sig.json", signatureBytes);
            VerifiedIndex verifiedIndex = new PluginArtifactIndexVerifier(trust)
                .requireTrusted(work.directory().resolve("index.json"), work.directory().resolve("index.sig.json"));
            var index = verifiedIndex.index();
            if (!sources.indexId().equals(index.indexId()) || !sources.indexRevision().equals(index.revision())
                    || !sources.indexContentHash().equals(index.contentHash())) {
                throw new SecurityException("downloaded index differs from the requested immutable revision");
            }
            Map<String, byte[]> files = trustFiles(verifiedTrust);
            var admission = admit(verifiedIndex, request, trust, files, work.directory(),
                (entry, type) -> switch (type) {
                    case ARTIFACT -> budget.download(URI.create(entry.artifactUri()), limits.artifactBytes());
                    case SIGNATURE -> budget.json(URI.create(entry.signatureManifestUri()));
                    case PROVENANCE -> budget.json(URI.create(entry.provenanceUri()));
                });
            put(files, "index.sig.json", PluginArtifactIndexSignature.read(work.directory().resolve("index.sig.json"))
                .toCanonicalJson());
            put(files, "retrieval.json", budget.evidence(sources));
            String operation = current == null || current.evidence().artifacts().isEmpty() ? "INSTALL" : "UPDATE";
            return commit(previous, operation, "", verifiedTrust.checkpoint(), trust,
                verifiedIndex.index(), admission, files);
        }
    }

    /** Removes the whole managed closure while retaining its history and current trust checkpoint. */
    public PluginInstallationEvidence remove() throws IOException {
        AcceptedState previous = accepted();
        Snapshot current = requireInstalled(active(previous));
        try (var work = store.work()) {
            PluginTrustStore trust = retainedTrust(current, work.directory());
            Map<String, byte[]> files = retainedTrustFiles(current);
            put(files, "retrieval.json", PluginDistributionJson.canonical(Map.of(
                "schema", "regelsuche.plugin-distribution-retrieval/v1", "networkAccessStatus", "NOT_PERFORMED")));
            return commit(previous, "REMOVE", "", previous.checkpoint(), trust, null,
                new Admission(null, List.of()), files);
        }
    }

    /**
     * Restores only an ancestor from retained history. Index, artifact signatures
     * and provenance are rechecked using CURRENT trust; trust is never rolled back.
     */
    public PluginInstallationEvidence rollback(String installationHash) throws IOException {
        PluginSignatureManifest.requireSha256(installationHash, "installationHash");
        AcceptedState previous = accepted();
        Snapshot current = active(previous);
        if (current == null) {
            throw new SecurityException("there is no installation history");
        }
        Snapshot target = ancestor(current, installationHash);
        try (var work = store.work()) {
            PluginTrustStore trust = retainedTrust(current, work.directory());
            Map<String, byte[]> files = retainedTrustFiles(current);
            put(files, "retrieval.json", PluginDistributionJson.canonical(Map.of(
                "schema", "regelsuche.plugin-distribution-retrieval/v1",
                "networkAccessStatus", "NOT_PERFORMED", "retainedGenerationHash", installationHash)));
            if (target.evidence().artifacts().isEmpty()) {
                return commit(previous, "ROLLBACK", installationHash, previous.checkpoint(), trust, null,
                    new Admission(null, List.of()), files);
            }
            byte[] indexBytes = required(target.files(), "index.json");
            byte[] signatureBytes = required(target.files(), "index.sig.json");
            PluginInstallationStore.write(work.directory(), "index.json", indexBytes);
            PluginInstallationStore.write(work.directory(), "index.sig.json", signatureBytes);
            VerifiedIndex index = new PluginArtifactIndexVerifier(trust).requireTrusted(
                work.directory().resolve("index.json"), work.directory().resolve("index.sig.json"));
            ResolutionReceipt original = PluginDistributionJson.read(required(target.files(), "resolution.json"),
                ResolutionReceipt.class);
            var admission = admit(index, original.request(), trust, files, work.directory(),
                (entry, type) -> required(target.files(), path(entry, type)));
            if (!original.contentHash().equals(admission.resolution().contentHash())) {
                throw new SecurityException("retained resolution no longer reproduces the package closure");
            }
            files.put("index.sig.json", signatureBytes);
            return commit(previous, "ROLLBACK", installationHash, previous.checkpoint(), trust,
                index.index(), admission, files);
        }
    }

    /** Snapshot of the generation selected at the authority read; absent only before genesis. */
    public Optional<PluginInstallationEvidence> active() throws IOException {
        Snapshot snapshot = active(accepted());
        return Optional.ofNullable(snapshot == null ? null : snapshot.evidence());
    }

    /** Returns the exact verified retained snapshot, avoiding a path-based verify-then-swap race. */
    public byte[] readArtifact(String identityHash) throws IOException {
        PluginSignatureManifest.requireSha256(identityHash, "identityHash");
        Snapshot snapshot = requireInstalled(active(accepted()));
        var artifact = snapshot.evidence().artifacts().stream()
            .filter(value -> value.identityHash().equals(identityHash)).findFirst()
            .orElseThrow(() -> new SecurityException("artifact is not part of the active installation"));
        return required(snapshot.files(), artifact.path()).clone();
    }

    private Admission admit(VerifiedIndex verified, ResolutionRequest request, PluginTrustStore trust,
            Map<String, byte[]> files, Path work, Fetch fetch) throws IOException {
        var resolution = new PluginArtifactResolver().resolve(verified.index(), request);
        if (resolution.status() != PluginArtifactResolver.ResolutionStatus.RESOLVED
                || resolution.plan().size() > limits.maximumArtifacts()) {
            throw new SecurityException("package resolution failed or exceeds the artifact count budget");
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        verified.index().entries().forEach(entry -> entries.put(entry.identityHash(), entry));
        List<Entry> selected = resolution.plan().stream().map(step -> entries.get(step.identityHash())).toList();
        for (Entry entry : selected) {
            if (entry == null || entry.kind() != ArtifactKind.JAVA_PLUGIN) {
                throw new SecurityException("network installation currently admits Java plugin JARs only");
            }
            PluginInstallationEvidence.requirePath(entry.artifactFileName());
            https(URI.create(entry.artifactUri()));
            https(URI.create(entry.signatureManifestUri()));
            https(URI.create(entry.provenanceUri()));
        }
        put(files, "index.json", verified.index().toCanonicalJson());
        put(files, "index-verification.json", verified.verification().toCanonicalJson());
        put(files, "resolution.json", resolution.toCanonicalJson());
        List<PluginInstallationEvidence.Artifact> artifacts = new ArrayList<>();
        for (Entry entry : selected) {
            byte[] artifact = fetch.get(entry, Resource.ARTIFACT);
            byte[] signature = fetch.get(entry, Resource.SIGNATURE);
            byte[] provenanceBytes = fetch.get(entry, Resource.PROVENANCE);
            PluginDistributionJson.validate(signature);
            PluginDistributionJson.validate(provenanceBytes);
            PluginInstallationStore.write(work, path(entry, Resource.ARTIFACT), artifact);
            PluginInstallationStore.write(work, path(entry, Resource.SIGNATURE), signature);
            var snapshot = new PluginArtifactVerifier(trust, limits.artifactBytes())
                .snapshot(work.resolve(path(entry, Resource.ARTIFACT)));
            var verification = snapshot.verification();
            if (!verification.permittedBy(PluginTrustPolicy.REQUIRE_VERIFIED)
                    || !entry.artifactSha256().equals(verification.artifactSha256())
                    || !entry.publisherId().equals(verification.publisherId())) {
                throw new SecurityException("artifact admission failed: " + verification.status());
            }
            var provenance = PluginArtifactProvenance.read(provenanceBytes);
            provenance.requireTrusted(entry, trust);
            files.put(path(entry, Resource.ARTIFACT), snapshot.artifactBytes());
            put(files, path(entry, Resource.SIGNATURE), PluginSignatureManifest.read(
                work.resolve(path(entry, Resource.SIGNATURE))).toCanonicalJson());
            put(files, path(entry, Resource.PROVENANCE), provenance.toCanonicalJson());
            put(files, directory(entry) + "/artifact-verification.json", verification.toCanonicalJson());
            artifacts.add(new PluginInstallationEvidence.Artifact(entry.kind().name(), entry.componentId(),
                entry.version(), entry.identityHash(), entry.artifactSha256(), entry.publisherId(),
                path(entry, Resource.ARTIFACT), provenance.contentHash(), verification.contentHash()));
        }
        return new Admission(resolution, artifacts);
    }

    private PluginInstallationEvidence commit(AcceptedState previous, String operation, String rollbackSource,
            PluginTrustStoreRevisionVerifier.ChainCheckpoint checkpoint, PluginTrustStore trust,
            PluginArtifactIndex index, Admission admission, Map<String, byte[]> files) throws IOException {
        long total = 0;
        for (var entry : files.entrySet()) {
            boolean artifact = admission.artifacts().stream().anyMatch(value -> value.path().equals(entry.getKey()));
            if (entry.getValue().length > (artifact ? limits.artifactBytes() : limits.metadataBytes())) {
                throw new SecurityException("retained file exceeds the installation byte limit");
            }
            total = Math.addExact(total, entry.getValue().length);
            if (total > limits.totalBytes()) {
                throw new SecurityException("retained generation exceeds the total installation byte limit");
            }
        }
        var evidence = PluginInstallationEvidence.create(operation, previous.installationHash(), rollbackSource,
            checkpoint, rootHash, PluginDistributionJson.hash(trust.toCanonicalJson()),
            index == null ? "" : index.indexId(), index == null ? "" : index.revision(),
            index == null ? "" : index.contentHash(),
            admission.resolution() == null ? "" : admission.resolution().contentHash(),
            admission.artifacts(), PluginInstallationStore.hashes(files));
        int installationBytes = evidence.toCanonicalJson().getBytes(StandardCharsets.UTF_8).length;
        if (installationBytes > limits.metadataBytes()) {
            throw new SecurityException("installation evidence exceeds the metadata byte limit");
        }
        if (installationBytes > limits.totalBytes() - total) {
            throw new SecurityException("retained generation exceeds the total installation byte limit");
        }
        store.persist(evidence, files);
        if (!authority.compareAndSet(previous, new AcceptedState(evidence.contentHash(), checkpoint))) {
            throw new SecurityException("installation authority rejected a stale or concurrent transition");
        }
        return evidence;
    }

    private AcceptedState accepted() throws IOException {
        AcceptedState accepted = Objects.requireNonNull(authority.read(), "authority cannot return null/genesis on failure");
        if (accepted.checkpoint() != null && !trustDomain.equals(accepted.checkpoint().trustDomainId())) {
            throw new SecurityException("authority belongs to another trust domain");
        }
        return accepted;
    }

    private Snapshot active(AcceptedState accepted) throws IOException {
        if (accepted.installationHash().isEmpty()) {
            return null;
        }
        Snapshot snapshot = store.load(accepted.installationHash());
        if (!accepted.checkpoint().equals(snapshot.evidence().checkpoint())
                || !rootHash.equals(snapshot.evidence().rootTrustStoreHash())) {
            throw new SecurityException("installation differs from trusted checkpoint or pinned authority roots");
        }
        return snapshot;
    }

    private Snapshot ancestor(Snapshot current, String hash) throws IOException {
        String cursor = current.evidence().previousInstallationHash();
        for (int count = 0; !cursor.isEmpty() && count < limits.maximumHistory(); count++) {
            Snapshot candidate = store.load(cursor);
            if (cursor.equals(hash)) {
                return candidate;
            }
            cursor = candidate.evidence().previousInstallationHash();
        }
        throw new SecurityException("rollback target is not within the bounded retained ancestry");
    }

    private static Snapshot requireInstalled(Snapshot snapshot) {
        if (snapshot == null || snapshot.evidence().artifacts().isEmpty()) {
            throw new SecurityException("no package closure is installed");
        }
        return snapshot;
    }

    private static PluginTrustStore retainedTrust(Snapshot snapshot, Path work) throws IOException {
        PluginInstallationStore.write(work, "current-trust.json", required(snapshot.files(), "trust-store.json"));
        PluginTrustStore trust = PluginTrustStore.load(work.resolve("current-trust.json"));
        if (!snapshot.evidence().trustStoreHash().equals(PluginDistributionJson.hash(trust.toCanonicalJson()))) {
            throw new SecurityException("retained working trust-store identity mismatch");
        }
        return trust;
    }

    private static Map<String, byte[]> trustFiles(VerifiedTrustStoreRevision trust) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        put(files, "trust-store.json", trust.trustStore().toCanonicalJson());
        put(files, "trust-revision.json", trust.revision().toCanonicalJson());
        put(files, "trust-verification.json", trust.verification().toCanonicalJson());
        put(files, "checkpoint.json", trust.checkpoint().toCanonicalJson());
        return files;
    }

    private static Map<String, byte[]> retainedTrustFiles(Snapshot snapshot) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        TRUST_FILES.forEach(name -> files.put(name, required(snapshot.files(), name)));
        return files;
    }

    private static byte[] required(Map<String, byte[]> files, String path) {
        byte[] bytes = files.get(path);
        if (bytes == null) {
            throw new SecurityException("installation lacks retained metadata: " + path);
        }
        return bytes;
    }

    private static void put(Map<String, byte[]> files, String path, String json) {
        files.put(path, json.getBytes(StandardCharsets.UTF_8));
    }

    private static String directory(Entry entry) {
        return "artifacts/" + entry.identityHash().substring(7);
    }

    private static String path(Entry entry, Resource type) {
        String artifact = directory(entry) + "/" + entry.artifactFileName();
        return switch (type) {
            case ARTIFACT -> artifact;
            case SIGNATURE -> artifact + ".sig.json";
            case PROVENANCE -> directory(entry) + "/provenance.json";
        };
    }

    private static URI https(URI uri) {
        Objects.requireNonNull(uri, "URI");
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new SecurityException("package installation requires an absolute HTTPS URI");
        }
        return uri;
    }

    /** Caller pins the immutable index identity as well as its retrieval locations. */
    public record Sources(URI trustStoreUri, URI trustRevisionUri, URI indexUri, URI indexSignatureUri,
        String indexId, String indexRevision, String indexContentHash) {
        public Sources {
            https(trustStoreUri);
            https(trustRevisionUri);
            https(indexUri);
            https(indexSignatureUri);
            PluginSignatureManifest.requireIdentifier(indexId, "indexId");
            PluginSignatureManifest.requireIdentifier(indexRevision, "indexRevision");
            PluginSignatureManifest.requireSha256(indexContentHash, "indexContentHash");
        }
    }

    /** Finite memory, download, closure and ancestry budgets; endpoints have transport deadlines. */
    public record Limits(long metadataBytes, long artifactBytes, long totalBytes,
        int maximumArtifacts, int maximumHistory) {
        public Limits {
            if (metadataBytes < 1 || artifactBytes < 1 || totalBytes < 1
                    || metadataBytes > Integer.MAX_VALUE - 8 || artifactBytes > Integer.MAX_VALUE - 8
                    || totalBytes > Integer.MAX_VALUE - 8 || maximumArtifacts < 1 || maximumHistory < 1) {
                throw new IllegalArgumentException("distribution budgets must be positive and finite");
            }
        }
    }

    private enum Resource { ARTIFACT, SIGNATURE, PROVENANCE }
    private interface Fetch { byte[] get(Entry entry, Resource type) throws IOException; }
    private record Admission(ResolutionReceipt resolution, List<PluginInstallationEvidence.Artifact> artifacts) { }

    private final class Budget {
        private long remaining = limits.totalBytes();
        private final List<Map<String, Object>> receipts = new ArrayList<>();

        byte[] json(URI uri) throws IOException {
            byte[] bytes = download(uri, limits.metadataBytes());
            PluginDistributionJson.validate(bytes);
            return bytes;
        }

        byte[] download(URI uri, long limit) throws IOException {
            if (remaining < 1 || receipts.size() >= 4L + 3L * limits.maximumArtifacts()) {
                throw new SecurityException("distribution download budget exhausted");
            }
            byte[] bytes = transport.download(https(uri), Math.min(limit, remaining));
            remaining -= bytes.length;
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("uri", uri.toString());
            receipt.put("bytes", bytes.length);
            receipt.put("sha256", PluginArtifactVerifier.sha256(bytes));
            receipts.add(receipt);
            return bytes;
        }

        String evidence(Sources sources) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schema", "regelsuche.plugin-distribution-retrieval/v1");
            payload.put("networkAccessStatus", "PERFORMED");
            payload.put("sources", sources);
            payload.put("resources", receipts);
            return PluginDistributionJson.canonical(payload);
        }
    }
}
