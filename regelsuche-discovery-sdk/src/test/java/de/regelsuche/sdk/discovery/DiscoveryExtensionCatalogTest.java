package de.regelsuche.sdk.discovery;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.discovery.domain.DiscoveryDomain;
import de.regelsuche.extension.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DiscoveryExtensionCatalogTest {
    private static final String HASH = "sha256:" + "a".repeat(64);
    private static final String OTHER = "sha256:" + "b".repeat(64);

    @Test void retainsInstalledOriginWithoutGrantingProof() {
        var entry = entry(HASH, HASH);
        var run = run(entry);
        var origin = entry.extensionProvenance().orElseThrow();
        assertEquals(DiscoveryExtensionPoints.DOMAIN_PROVIDERS, origin.point());
        var properties = run.evidence().domainEvidence().properties();
        assertEquals(Map.of(
            "sdk.provider.extension.pointId", "discovery.domain-provider",
            "sdk.provider.extension.contributionId", "contribution",
            "sdk.provider.extension.sourceKind", "EXTERNAL_PLUGIN",
            "sdk.provider.extension.sourceId", "plugin",
            "sdk.provider.extension.sourceVersion", "2",
            "sdk.provider.extension.sourceReference", "installed-reference",
            "sdk.provider.extension.artifactSha256", HASH,
            "sdk.provider.extension.trustEvidenceSha256", HASH,
            "sdk.provider.extension.catalogSha256", HASH),
            properties.entrySet().stream().filter(e -> e.getKey().startsWith("sdk.provider.extension."))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        assertEquals(entry.artifact().orElseThrow().artifactSha256(), properties.get("sdk.provider.artifactSha256"));
        assertEquals("NOT_EVALUATED", properties.get("sdk.provider.authority"));
        assertEquals("NOT_EVALUATED", run.evidence().proofStatus());
        assertTrue(DiscoveryDomainCatalog.fromProviders(List.of(provider("provider", DiscoverySdkTest.sampleDomain())))
            .registrations().getFirst().extensionProvenance().isEmpty());
    }

    @Test void originChangesEvidenceIdentityButNotCandidate() {
        var a = run(entry(HASH, HASH));
        var b = run(entry(OTHER, OTHER));
        var catalogOnly = run(entry(HASH, OTHER));
        assertEquals(a.outcome(), b.outcome());
        assertEquals(a.evidence().selectedCandidateHash(), b.evidence().selectedCandidateHash());
        assertNotEquals(a.evidence().contentHash(), b.evidence().contentHash());
        assertEquals(a.outcome(), catalogOnly.outcome());
        assertEquals(a.evidence().selectedCandidateHash(), catalogOnly.evidence().selectedCandidateHash());
        assertNotEquals(a.evidence().contentHash(), catalogOnly.evidence().contentHash());
    }

    @Test void rejectsProviderAndDomainCollisionsAcrossContributions() {
        var first = provider("provider", DiscoverySdkTest.sampleDomain());
        assertThrows(IllegalArgumentException.class, () -> DiscoveryDomainCatalog.fromExtensions(
            catalog(HASH, contribution("one", first, HASH), contribution("two", first, HASH))));
        assertThrows(IllegalArgumentException.class, () -> DiscoveryDomainCatalog.fromExtensions(
            catalog(HASH, contribution("one", first, HASH), contribution("two",
                provider("other", DiscoverySdkTest.sampleDomain()), HASH))));
    }

    @Test void rejectsIncompatibleApiBeforeDomains() {
        var future = new DiscoveryDomainProvider() {
            public String id() { return "future"; }
            public String apiVersion() { return "99"; }
            public Collection<DiscoveryDomain<?, ?, ?>> domains() { fail("incompatible domains invoked"); return List.of(); }
        };
        assertThrows(IllegalArgumentException.class, () -> DiscoveryDomainCatalog.fromExtensions(
            catalog(HASH, contribution("future", future, HASH))));
    }

    @Test void rejectsReservedOriginOverride() {
        var delegate = DiscoverySdkTest.sampleDomain();
        var domain = (DiscoveryDomain<?, ?, ?>) java.lang.reflect.Proxy.newProxyInstance(
            DiscoveryDomain.class.getClassLoader(), new Class<?>[]{DiscoveryDomain.class},
            (proxy, method, args) -> {
                if (method.getName().equals("evidenceAdapter")) {
                    return new DiscoveryDomain.EvidenceAdapter<Object, Object, Object>() {
                        public String id() { return "reserved-adapter"; }
                        public DiscoveryDomain.DomainPayload adapt(Optional<Object> initial,
                                Optional<Object> candidate, Optional<Object> certificate) {
                            return new DiscoveryDomain.DomainPayload("test", Map.of(
                                "sdk.provider.extension.trustEvidenceSha256", OTHER));
                        }
                    };
                }
                return method.invoke(delegate, args);
            });
        var entry = DiscoveryDomainCatalog.fromExtensions(catalog(HASH,
            contribution("reserved", provider("provider", domain), HASH))).registrations().getFirst();
        assertThrows(IllegalArgumentException.class, () -> run(entry));
    }

    @Test void preservesDistinctContributorAndProviderArtifactIdentity() {
        var entry = entry(OTHER, HASH);
        assertEquals(OTHER, entry.extensionProvenance().orElseThrow().origin().artifactSha256());
        assertNotEquals(OTHER, entry.artifact().orElseThrow().artifactSha256());
        assertEquals(ProviderProvenance.capture(entryProviderClass()).artifactSha256(), entry.artifact().orElseThrow().artifactSha256());
    }

    @Test void validatesProvenanceWithoutClaimingAdmission() {
        var point = DiscoveryExtensionPoints.DOMAIN_PROVIDERS;
        var descriptor = ExtensionDescriptor.named("contribution");
        var origin = ExtensionOrigin.classpathPlugin("plugin", "1", "reference");
        assertThrows(NullPointerException.class,
            () -> new DiscoveryExtensionProvenance(null, descriptor, origin, HASH));
        assertThrows(NullPointerException.class,
            () -> new DiscoveryExtensionProvenance(point, null, origin, HASH));
        assertThrows(NullPointerException.class,
            () -> new DiscoveryExtensionProvenance(point, descriptor, null, HASH));
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryExtensionProvenance(
            ExtensionPoint.of("other.point", DiscoveryDomainProvider.class), descriptor, origin, HASH));
        assertThrows(IllegalArgumentException.class,
            () -> new DiscoveryExtensionProvenance(point, descriptor, origin, "invalid"));
        assertEquals(origin, new DiscoveryExtensionProvenance(point, descriptor, origin, HASH).origin());
        assertThrows(NullPointerException.class, () -> DiscoveryDomainCatalog.fromExtensions(null));
    }

    private static Class<?> entryProviderClass() { return provider("provider", DiscoverySdkTest.sampleDomain()).getClass(); }
    private static DiscoveryRun<?, ?> run(DiscoveryDomainCatalog.Registration entry) {
        return RegelsucheDiscovery.forRegistration(entry).campaign("extension")
            .seed("seed", "sequence", "test").run();
    }
    private static DiscoveryDomainCatalog.Registration entry(String artifact, String catalog) {
        return DiscoveryDomainCatalog.fromExtensions(catalog(catalog,
            contribution("contribution", provider("provider", DiscoverySdkTest.sampleDomain()), artifact)))
            .registrations().getFirst();
    }
    private static DiscoveryDomainProvider provider(String id, DiscoveryDomain<?, ?, ?> domain) {
        return new DiscoveryDomainProvider() {
            public String id() { return id; }
            public Collection<DiscoveryDomain<?, ?, ?>> domains() { return List.of(domain); }
        };
    }
    private static RegisteredExtension<DiscoveryDomainProvider> contribution(String id, DiscoveryDomainProvider provider, String artifact) {
        return new RegisteredExtension<>(DiscoveryExtensionPoints.DOMAIN_PROVIDERS,
            ExtensionDescriptor.named(id), provider,
            ExtensionOrigin.externalPlugin("plugin", "2", "installed-reference", artifact, artifact));
    }
    @SafeVarargs private static ExtensionCatalog catalog(String hash, RegisteredExtension<DiscoveryDomainProvider>... entries) {
        var values = List.of(entries);
        return new ExtensionCatalog() {
            public <T> List<RegisteredExtension<T>> registrations(ExtensionPoint<T> point) {
                assertEquals(DiscoveryExtensionPoints.DOMAIN_PROVIDERS, point);
                return values.stream().map(e -> new RegisteredExtension<>(point, e.descriptor(),
                    point.contractType().cast(e.implementation()), e.origin())).toList();
            }
            public <T> Optional<RegisteredExtension<T>> find(ExtensionPoint<T> point, String id) {
                return registrations(point).stream().filter(e -> e.descriptor().id().equals(id)).findFirst();
            }
            public String canonicalManifest() { return "metadata fixture; no cryptographic admission asserted"; }
            public String contentHash() { return hash; }
        };
    }
}
