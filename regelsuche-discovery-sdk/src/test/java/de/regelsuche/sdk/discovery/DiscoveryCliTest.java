package de.regelsuche.sdk.discovery;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.sdk.discovery.cli.DiscoveryCli;
import de.regelsuche.extension.*;
import de.regelsuche.discovery.domain.DiscoveryDomain;
import de.regelsuche.discovery.domain.DiscoveryDomain.*;
import java.util.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryCliTest {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private int execute(String... args) {
        return DiscoveryCli.execute(args, new PrintStream(output), getClass().getClassLoader());
    }
    @Test void rejectsUnknownBudgetBeforeLoadingAnyProvider() {
        var touched = new java.util.concurrent.atomic.AtomicBoolean();
        var loader = new ClassLoader(getClass().getClassLoader()) {
            @Override public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
                if (name.equals("META-INF/services/" + DiscoveryDomainProvider.class.getName())) touched.set(true);
                return super.getResources(name);
            }
        };
        assertEquals(1, DiscoveryCli.execute(new String[]{"run", TestDiscoveryDomainProvider.class.getName(),
            "sdk-multiplier-search@v1", "campaign", "missing-seed", "unused", "unbounded"}, new PrintStream(output), loader));
        assertFalse(touched.get(), "invalid budget must fail before service discovery/provider callbacks");
    }
    @Test void listsClassesAndExplicitlySelectedDomain() {
        assertEquals(0, execute("list"));
        assertTrue(output.toString().contains(TestDiscoveryDomainProvider.class.getName()));
        assertEquals(0, execute("list", TestDiscoveryDomainProvider.class.getName()));
        assertTrue(output.toString().contains("sdk-multiplier-search@v1"));
        assertTrue(output.toString().contains("artifact=sha256:"));
        assertEquals(1, execute());
        assertTrue(output.toString().contains("Usage: list [provider.class]"));
        assertTrue(output.toString().contains("From the Regelsuche app: domains list [provider.class]"));
        assertEquals(1, execute("list", "missing.Provider"));
    }
    @Test void retainsEvidenceForConfirmedAndBudgetExhaustedRuns(@TempDir Path temp) throws Exception {
        Path seed = Files.writeString(temp.resolve("seed.txt"), "sequence");
        Path result = temp.resolve("confirmed.json");
        String provider = TestDiscoveryDomainProvider.class.getName();
        assertEquals(0, execute("run", provider, "sdk-multiplier-search@v1", "cli-test", seed.toString(), result.toString()));
        assertTrue(Files.readString(result).contains("sdk.provider.artifactSha256"));
        assertEquals(1, execute("run", provider, "sdk-multiplier-search@v1", "cli-test", seed.toString(), result.toString()));
        Path exhausted = temp.resolve("exhausted.json");
        assertEquals(3, execute("run", provider, "sdk-multiplier-search@v1", "cli-test", seed.toString(), exhausted.toString(), "tiny"));
        assertTrue(Files.readString(exhausted).contains("BUDGET_EXHAUSTED"));
        assertEquals(1, execute("run", provider, "sdk-multiplier-search@v1", "cli-test", seed.toString(), "unused", "unbounded"));
        assertEquals(1, execute("run", provider, "missing@v1", "cli-test", seed.toString(), "unused"));
        assertEquals(1, execute("run", provider, "missing", "cli-test", seed.toString(), "unused"));
        assertEquals(1, execute("run", provider, "sdk-multiplier-search@v1", "cli-test", "missing-seed", "unused"));
    }
    @Test void rejectsOversizedAndMalformedUtf8Seeds(@TempDir Path temp) throws Exception {
        Path seed = Files.writeString(temp.resolve("large.txt"), "x".repeat(1024 * 1024 + 1));
        Path result = temp.resolve("result.json");
        String provider = TestDiscoveryDomainProvider.class.getName();
        assertEquals(1, execute("run", provider, "sdk-multiplier-search@v1", "cli-test", seed.toString(), result.toString()));
        assertFalse(Files.exists(result));
        Files.write(seed, new byte[]{(byte) 0xff});
        assertEquals(1, execute("run", provider, "sdk-multiplier-search@v1", "cli-test", seed.toString(), result.toString()));
        assertFalse(Files.exists(result));
    }
    private int execute(DiscoveryDomainCatalog catalog, String... args) {
        return DiscoveryCli.execute(args, new PrintStream(output), catalog);
    }
    private static DiscoveryDomainCatalog catalog(String providerId, DiscoveryDomain<?, ?, ?> domain) {
        return DiscoveryDomainCatalog.fromProviders(List.of(new DiscoveryDomainProvider() {
            public String id() { return providerId; }
            public Collection<DiscoveryDomain<?, ?, ?>> domains() { return List.of(domain); }
        }));
    }
    @Test void listsExplicitCatalogAndRejectsClasspathSyntax() {
        var catalog = catalog("provider-id", DiscoverySdkTest.sampleDomain());
        assertEquals(0, execute(catalog, "list"));
        assertTrue(output.toString().contains("sdk-multiplier-search@v1"));
        assertTrue(output.toString().contains("provider=provider-id"));
        assertTrue(output.toString().contains("version=1"));
        assertTrue(output.toString().contains("artifact=sha256:"));
        assertEquals(0, execute(DiscoveryDomainCatalog.fromProviders(List.of()), "list"));
        assertEquals(1, execute(catalog, "list", "provider-id"));
        assertEquals(1, execute(catalog));
        assertTrue(output.toString().contains("run provider-id"));
    }
    @Test void listsContributionOriginFromExplicitCatalog() {
        String artifact = "sha256:" + "a".repeat(64);
        String trust = "sha256:" + "b".repeat(64);
        var contribution = new RegisteredExtension<>(DiscoveryExtensionPoints.DOMAIN_PROVIDERS,
            ExtensionDescriptor.named("discovery-contribution"), new TestDiscoveryDomainProvider(),
            ExtensionOrigin.externalPlugin("plugin", "2", "installed-reference", artifact, trust));
        var extensions = new ExtensionCatalog() {
            public <T> List<RegisteredExtension<T>> registrations(ExtensionPoint<T> point) {
                return List.of(new RegisteredExtension<>(point, contribution.descriptor(),
                    point.contractType().cast(contribution.implementation()), contribution.origin()));
            }
            public <T> Optional<RegisteredExtension<T>> find(ExtensionPoint<T> point, String id) {
                return registrations(point).stream().filter(entry -> entry.descriptor().id().equals(id)).findFirst();
            }
            public String contentHash() { return trust; }
            public String canonicalManifest() { return "metadata fixture without admission claim"; }
        };
        assertEquals(0, execute(DiscoveryDomainCatalog.fromExtensions(extensions), "list"));
        String listing = output.toString();
        assertTrue(listing.contains("provider=sdk-test-provider version=1"));
        assertTrue(listing.contains("contribution=discovery-contribution"));
        assertTrue(listing.contains("contributorArtifact=" + artifact));
        assertTrue(listing.contains("trustEvidence=" + trust));
    }
    @Test void explicitCatalogRequiresBothProviderAndExactRevision(@TempDir Path temp) throws Exception {
        var catalog = DiscoveryDomainCatalog.fromProviders(List.of(new TestDiscoveryDomainProvider(),
            new DiscoveryDomainProvider() {
                public String id() { return "empty-provider"; }
                public Collection<DiscoveryDomain<?, ?, ?>> domains() { return List.of(); }
            }));
        Path seed = Files.writeString(temp.resolve("seed.txt"), "sequence");
        Path result = temp.resolve("result.json");
        for (String provider : List.of("empty-provider", "unknown", TestDiscoveryDomainProvider.class.getName())) {
            assertEquals(1, execute(catalog, "run", provider, "sdk-multiplier-search@v1", "cli", seed.toString(), result.toString()));
            assertFalse(Files.exists(result));
        }
        for (String selector : List.of("sdk-multiplier-search", "sdk-multiplier-search@", "@v1",
                "sdk-multiplier-search@v2", "sdk-multiplier-search@v1@v1")) {
            assertEquals(1, execute(catalog, "run", "sdk-test-provider", selector, "cli", seed.toString(), result.toString()));
            assertFalse(Files.exists(result));
        }
        assertEquals(1, execute(catalog, "run", "sdk-test-provider", "sdk-multiplier-search@v1", "cli", seed.toString(), result.toString(), "unbounded"));
        assertFalse(Files.exists(result));
        assertEquals(0, execute(catalog, "run", "sdk-test-provider", "sdk-multiplier-search@v1", "cli", seed.toString(), result.toString()));
        String evidence = Files.readString(result);
        Path legacy = temp.resolve("legacy.json");
        assertEquals(0, execute("run", TestDiscoveryDomainProvider.class.getName(), "sdk-multiplier-search@v1", "cli", seed.toString(), legacy.toString()));
        assertEquals(evidence, Files.readString(legacy));
        assertTrue(evidence.contains("sdk.provider.artifactSha256"));
        assertTrue(output.toString().contains("outcome=CONFIRMED"));
        assertTrue(output.toString().contains("evidenceHash=sha256:"));
        assertEquals(1, execute(catalog, "run", "sdk-test-provider", "sdk-multiplier-search@v1", "cli", seed.toString(), result.toString()));
        assertEquals(evidence, Files.readString(result));
        Path tiny = temp.resolve("tiny.json");
        assertEquals(3, execute(catalog, "run", "sdk-test-provider", "sdk-multiplier-search@v1", "cli", seed.toString(), tiny.toString(), "tiny"));
        assertTrue(Files.readString(tiny).contains("BUDGET_EXHAUSTED"));
    }
    @Test void explicitCatalogRejectsInvalidSeedBytesBeforeExport(@TempDir Path temp) throws Exception {
        var catalog = catalog("provider", DiscoverySdkTest.sampleDomain());
        Path seed = Files.write(temp.resolve("seed.txt"), new byte[]{(byte) 0xc3, 0x28});
        Path result = temp.resolve("result.json");
        assertEquals(1, execute(catalog, "run", "provider", "sdk-multiplier-search@v1", "cli", seed.toString(), result.toString()));
        assertFalse(Files.exists(result));
        Files.writeString(seed, "x".repeat(1024 * 1024 + 1));
        assertEquals(1, execute(catalog, "run", "provider", "sdk-multiplier-search@v1", "cli", seed.toString(), result.toString()));
        assertFalse(Files.exists(result));
    }
    @Test void explicitCatalogPreservesEverySemanticExitCode(@TempDir Path temp) throws Exception {
        Path seed = Files.writeString(temp.resolve("seed.txt"), "state=0");
        var evaluations = List.of(Evaluation.<Integer>refuted("rejected", Map.of()),
            Evaluation.<Integer>inconclusive("insufficient", Map.of()),
            Evaluation.<Integer>unsupported("outside scope", Map.of()));
        int[] codes = {2, 4, 5, 6};
        String[] outcomes = {"REFUTED", "INCONCLUSIVE", "UNSUPPORTED", "INVALID_SEED"};
        for (int index = 0; index < codes.length; index++) {
            boolean valid = index != 3;
            var evaluation = evaluations.get(Math.min(index, 2));
            var domain = DiscoveryDomainBuilder.<Integer, Integer, Integer>domain("outcome", "v1")
                .generator(input -> List.of(0)).stateCodec(Object::toString)
                .invariant("valid", state -> valid ? InvariantResult.pass() : InvariantResult.fail("invalid seed"))
                .operator("finished", state -> List.of())
                .objective(state -> new ObjectiveAssessment(1, true, Map.of()))
                .candidate(context -> context.currentState(), Object::toString)
                .counterexamples((candidate, budget) -> CounterexampleResult.noneFound(1, Map.of()))
                .evaluator(candidate -> evaluation)
                .certificate("FIXTURE", Object::toString, Object::toString).build();
            Path result = temp.resolve(outcomes[index] + ".json");
            assertEquals(codes[index], execute(catalog("provider", domain), "run", "provider", "outcome@v1", "cli", seed.toString(), result.toString()));
            assertTrue(Files.readString(result).contains("\"outcome\":\"" + outcomes[index] + "\""));
        }
    }

}
