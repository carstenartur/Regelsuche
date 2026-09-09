package de.regelsuche.sdk.discovery;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.discovery.domain.DiscoveryDomain;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderProvenanceTest {
    @Test void retainedOriginChangesHashWithoutChangingMathematicalResult() {
        var domain = DiscoverySdkTest.sampleDomain();
        var entry = DiscoveryDomainCatalog.fromProviders(List.of(provider("first", "1", domain)))
            .registrations().getFirst();
        var registered = RegelsucheDiscovery.forRegistration(entry).campaign("origin")
            .seed("seed", "sequence", "test").run();
        var direct = RegelsucheDiscovery.forDomain(domain).campaign("origin")
            .seed("seed", "sequence", "test").run();
        assertEquals(direct.outcome(), registered.outcome());
        assertEquals(direct.evidence().selectedCandidateHash(), registered.evidence().selectedCandidateHash());
        assertNotEquals(direct.evidence().contentHash(), registered.evidence().contentHash());
        assertEquals("first", registered.evidence().domainEvidence().properties().get("sdk.provider.id"));
        assertEquals(entry.artifact().orElseThrow().artifactSha256(),
            registered.evidence().domainEvidence().properties().get("sdk.provider.artifactSha256"));
        var replay = RegelsucheDiscovery.forRegistration(entry).campaign("origin")
            .seed("seed", "sequence", "test").run();
        assertArrayEquals(registered.evidenceBytes(), replay.evidenceBytes());
        assertArrayEquals(registered.evidenceBytes(), registered.replay().evidenceBytes());
        assertEquals("NOT_EVALUATED", registered.evidence().proofStatus());
        var shortRun = RegelsucheDiscovery.forRegistration(entry).campaign("short")
            .seed("seed", "sequence", "test").budget(DiscoveryBudgets.tiny()).run();
        DiscoveryRunAssertions.assertThat(shortRun).isBudgetExhausted();
        assertEquals("first", shortRun.evidence().domainEvidence().properties().get("sdk.provider.id"));
    }

    @Test void rejectsManualRegistrationWithoutObservedArtifactProvenance() {
        var manual = new DiscoveryDomainCatalog.Registration(
            "manual-provider", "1", "source-reference", DiscoverySdkTest.sampleDomain());
        var error = assertThrows(IllegalArgumentException.class,
            () -> RegelsucheDiscovery.forRegistration(manual));
        assertTrue(error.getMessage().contains("host-observed provider artifact provenance"));
    }

    @Test void rejectsIncompatibleProviderBeforeCallingItsDomainFactory() {
        var provider = new DiscoveryDomainProvider() {
            public String id() { return "future-provider"; }
            public String apiVersion() { return "99"; }
            public Collection<DiscoveryDomain<?, ?, ?>> domains() { fail("must not initialize incompatible domains"); return List.of(); }
        };
        var error = assertThrows(IllegalArgumentException.class,
            () -> DiscoveryDomainCatalog.fromProviders(List.of(provider)));
        assertTrue(error.getMessage().contains("requires API 99"));
    }

    @Test void disablesProvidersAndDiagnosesMissingEnabledClass() {
        var loader = getClass().getClassLoader();
        assertTrue(DiscoveryDomainCatalog.load(loader, Set.of()).registrations().isEmpty());
        assertEquals(1, DiscoveryDomainCatalog.load(loader,
            Set.of(TestDiscoveryDomainProvider.class.getName())).registrations().size());
        assertThrows(IllegalArgumentException.class, () -> DiscoveryDomainCatalog.load(loader, Set.of("missing.Provider")));
    }

    @Test void diagnosesDuplicateIdsAndRevisions() {
        var domain = DiscoverySdkTest.sampleDomain();
        assertThrows(IllegalArgumentException.class, () -> DiscoveryDomainCatalog.fromProviders(
            List.of(provider("same", "1", domain), provider("same", "1", domain))));
        assertThrows(IllegalArgumentException.class, () -> DiscoveryDomainCatalog.fromProviders(
            List.of(provider("first", "1", domain), provider("second", "1", domain))));
        assertThrows(IllegalArgumentException.class, () -> DiscoveryDomainCatalog.fromProviders(
            List.of(provider("bad id", "1", domain))));
        assertThrows(IllegalArgumentException.class, () -> DiscoveryDomainCatalog.fromProviders(
            List.of(provider("valid-id", "bad version", domain))));
    }

    @Test void fingerprintsActualJarBytesAndDetectsChangedResources(@TempDir Path temp) throws Exception {
        Path first = jar(temp.resolve("first.jar"), "first");
        Path second = jar(temp.resolve("second.jar"), "second");
        var a = fingerprint(first);
        var b = fingerprint(second);
        assertEquals("JAR", a.artifactKind());
        assertEquals("sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(first))), a.artifactSha256());
        assertNotEquals(a.artifactSha256(), b.artifactSha256());
    }

    @Test void rejectsMissingOrMalformedArtifactMetadata() {
        assertThrows(IllegalArgumentException.class, () -> ProviderProvenance.capture(String.class));
        assertThrows(IllegalArgumentException.class, () -> new ProviderProvenance("", "JAR", "sha256:" + "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new ProviderProvenance("Provider", "REMOTE", "sha256:" + "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new ProviderProvenance("Provider", "JAR", "wrong"));
    }

    @Test void evidenceExportNeverOverwritesAnArtifact(@TempDir Path temp) throws Exception {
        var run = RegelsucheDiscovery.forDomain(DiscoverySdkTest.sampleDomain()).campaign("export")
            .seed("seed", "sequence", "test").run();
        Path path = temp.resolve("evidence.json");
        assertEquals(path, run.writeEvidence(path));
        assertArrayEquals(run.evidenceBytes(), Files.readAllBytes(path));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> run.writeEvidence(path));
        assertTrue(run.assumptions().isEmpty());
    }

    private static DiscoveryDomainProvider provider(String id, String version, DiscoveryDomain<?, ?, ?> domain) {
        return new DiscoveryDomainProvider() {
            public String id() { return id; }
            public String version() { return version; }
            public String provenance() { return "source-reference"; }
            public Collection<DiscoveryDomain<?, ?, ?>> domains() { return List.of(domain); }
        };
    }

    private static Path jar(Path path, String resource) throws Exception {
        try (var out = new JarOutputStream(Files.newOutputStream(path))) {
            String name = PackagedProvider.class.getName().replace('.', '/') + ".class";
            out.putNextEntry(new JarEntry(name));
            try (var in = PackagedProvider.class.getResourceAsStream("/" + name)) { in.transferTo(out); }
            out.closeEntry();
            out.putNextEntry(new JarEntry("provider-data.txt"));
            out.write(resource.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return path;
    }

    private static ProviderProvenance fingerprint(Path jar) throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, ProviderProvenanceTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(PackagedProvider.class.getName())) return findClass(name);
                return super.loadClass(name, resolve);
            }
        }) {
            return ProviderProvenance.capture(loader.loadClass(PackagedProvider.class.getName()));
        }
    }

    public static final class PackagedProvider implements DiscoveryDomainProvider {
        public String id() { return "jar-provider"; }
        public Collection<DiscoveryDomain<?, ?, ?>> domains() { return List.of(); }
    }
}
