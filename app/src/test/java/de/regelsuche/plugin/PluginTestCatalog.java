package de.regelsuche.plugin;

import static de.regelsuche.plugin.PluginDistributionFixtures.hash;
import static de.regelsuche.plugin.PluginDistributionFixtures.sign;

import de.regelsuche.plugin.PluginArtifactIndex.ArtifactKind;
import de.regelsuche.plugin.PluginArtifactIndex.Dependency;
import de.regelsuche.plugin.PluginArtifactIndex.Entry;
import de.regelsuche.plugin.PluginDistributionClient.Sources;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A loopback HTTPS test installation. Source pins and roots come from its test operator. */
final class PluginTestCatalog implements AutoCloseable {
    static final String TRUST_DOMAIN = "community";
    private final PluginDistributionFixtures distribution;
    private final boolean ownsDistribution;

    PluginTestCatalog() throws Exception {
        this(new PluginDistributionFixtures(), true);
    }

    PluginTestCatalog(PluginDistributionFixtures distribution) {
        this(distribution, false);
    }

    private PluginTestCatalog(PluginDistributionFixtures distribution, boolean ownsDistribution) {
        this.distribution = Objects.requireNonNull(distribution, "distribution");
        this.ownsDistribution = ownsDistribution;
    }

    URI origin() {
        return distribution.origin;
    }

    PluginTrustStore roots() {
        return distribution.roots;
    }

    PluginTrustStore publishers() {
        return distribution.publishers;
    }

    PluginDistributionTransport transport() {
        return new PluginDistributionTransport(Set.of(origin()), Duration.ofSeconds(2),
            Duration.ofSeconds(3), false, distribution.tls);
    }

    Entry publishArtifact(ArtifactKind kind, String component, String version, String api,
            String minimumCore, String maximumCore, List<String> capabilities,
            List<Dependency> dependencies, byte[] bytes) throws Exception {
        String extension = switch (kind) {
            case JAVA_PLUGIN -> ".jar";
            case RULE_PACKAGE -> ".regelsuche";
            case KNOWLEDGE_PACK -> ".json";
        };
        String file = component + "-" + version + extension;
        String digest = PluginArtifactVerifier.sha256(bytes);
        URI artifact = origin().resolve("/artifacts/" + digest.substring(7) + "/" + file);
        String signature = kind == ArtifactKind.JAVA_PLUGIN ? artifact + ".sig.json" : "";
        var entry = Entry.create("artifact-" + digest.substring(7),
            kind, component, version, api, minimumCore, maximumCore, capabilities, dependencies,
            file, digest, artifact.toString(), signature, artifact + ".provenance.json", "publisher");
        publishArtifact(entry, bytes);
        return entry;
    }

    void publishArtifact(Entry entry, byte[] bytes) throws Exception {
        byte[] artifact = bytes.clone();
        if (!entry.artifactSha256().equals(PluginArtifactVerifier.sha256(artifact))
                || !"publisher".equals(entry.publisherId())) {
            throw new IllegalArgumentException("artifact bytes or publisher differ from the test catalog entry");
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(path(URI.create(entry.artifactUri())), artifact);
        if (entry.kind() == ArtifactKind.JAVA_PLUGIN) {
            var signature = PluginSignatureManifest.create(entry.artifactFileName(), entry.artifactSha256(),
                entry.publisherId(), "key-1", sign(distribution.publisherKey,
                    PluginSignatureManifest.signedPayload(entry.artifactFileName(), entry.artifactSha256(),
                        entry.publisherId(), "key-1", "Ed25519")));
            if (files.putIfAbsent(path(URI.create(entry.signatureManifestUri())),
                    json(signature.toCanonicalJson())) != null) {
                throw new IllegalArgumentException("artifact and signature destinations must differ");
            }
        }
        var provenance = PluginArtifactProvenanceTest.signed(distribution, entry);
        if (files.putIfAbsent(path(URI.create(entry.provenanceUri())),
                json(provenance.toCanonicalJson())) != null) {
            throw new IllegalArgumentException("artifact and provenance destinations must differ");
        }
        publish(files);
    }

    Publication publishRevision(long sequence, String previousHash, PluginTrustStore workingStore,
            List<Entry> entries) throws Exception {
        var index = PluginArtifactIndex.create("community-index", "revision-" + sequence, "curator", entries);
        var signature = PluginArtifactIndexSignature.create(index.indexId(), index.revision(), index.contentHash(),
            index.curatorId(), "key-1", sign(distribution.curatorKey, PluginArtifactIndexSignature.signedPayload(
                index.indexId(), index.revision(), index.contentHash(), index.curatorId(), "key-1", "Ed25519")));
        String storeJson = workingStore.toCanonicalJson();
        String storeHash = hash(storeJson);
        var revision = PluginTrustStoreRevision.create(TRUST_DOMAIN, sequence, previousHash, storeHash,
            "authority", "key-1", sign(distribution.rootKey, PluginTrustStoreRevision.signedPayload(
                TRUST_DOMAIN, sequence, previousHash, storeHash, "authority", "key-1", "Ed25519")));

        URI indexDirectory = origin().resolve("/indexes/" + index.contentHash().substring(7) + "/");
        URI trustDirectory = origin().resolve("/trust/" + revision.contentHash().substring(7) + "/");
        var sources = new Sources(trustDirectory.resolve("store.json"), trustDirectory.resolve("revision.json"),
            indexDirectory.resolve("index.json"), indexDirectory.resolve("signature.json"),
            index.indexId(), index.revision(), index.contentHash());
        publish(Map.of(path(sources.trustStoreUri()), json(storeJson),
            path(sources.trustRevisionUri()), json(revision.toCanonicalJson()),
            path(sources.indexUri()), json(index.toCanonicalJson()),
            path(sources.indexSignatureUri()), json(signature.toCanonicalJson())));
        return new Publication(index, revision, sources);
    }

    URI redirectTo(URI target) {
        String route = "/test-redirect";
        distribution.server.createContext(route, exchange -> {
            try {
                exchange.getResponseHeaders().set("Location", target.toString());
                exchange.sendResponseHeaders(302, -1);
            } finally {
                exchange.close();
            }
        });
        return origin().resolve(route);
    }

    private String path(URI uri) {
        String path = uri.getPath();
        if (path == null || !origin().resolve(path).equals(uri)) {
            throw new IllegalArgumentException("publication URI must belong to the test catalog");
        }
        return path;
    }

    private void publish(Map<String, byte[]> files) {
        synchronized (distribution) {
            files.forEach((path, bytes) -> {
                byte[] published = distribution.responses.get(path);
                if (published != null && !Arrays.equals(published, bytes)) {
                    throw new IllegalArgumentException("test catalog resource is immutable: " + path);
                }
            });
            files.forEach(distribution.responses::putIfAbsent);
        }
    }

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (ownsDistribution) {
            distribution.close();
        }
    }

    record Publication(PluginArtifactIndex index, PluginTrustStoreRevision revision, Sources sources) { }
}
