package de.regelsuche.extension.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.extension.ExtensionApi;
import de.regelsuche.extension.ExtensionContext;
import de.regelsuche.extension.ExtensionDescriptor;
import de.regelsuche.extension.ExtensionPoint;
import de.regelsuche.extension.PluginDependency;
import de.regelsuche.extension.PluginDescriptor;
import de.regelsuche.extension.RegelsuchePlugin;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtensionRuntimeTest {
    private static final ExtensionPoint<Runnable> POINT =
        ExtensionPoint.of("fixture.runnable", Runnable.class);
    private static final ExtensionPoint<Runnable> EXTERNAL_POINT =
        ExtensionPoint.of("fixture.external", Runnable.class);

    @Test
    void duplicatePluginIdsRejectInitialSnapshot() {
        assertThrows(IllegalArgumentException.class,
            () -> ExtensionRuntime.open(ExtensionRuntimeConfig.explicit(List.of(
                plugin("same", "a"), plugin("same", "b")))));
    }

    @Test
    void duplicateContributionIdsAcrossPluginsRejectInitialSnapshot() {
        assertThrows(IllegalArgumentException.class,
            () -> ExtensionRuntime.open(ExtensionRuntimeConfig.explicit(List.of(
                plugin("one", "same"), plugin("two", "same")))));
    }

    @Test
    void failedReloadKeepsPreviousCatalogHash() {
        try (var runtime = ExtensionRuntime.open(
                ExtensionRuntimeConfig.explicit(List.of(plugin("good", "ok"))))) {
            String previous = runtime.catalog().contentHash();
            RegelsuchePlugin broken = pluginWithBody(
                descriptor("broken", "1", List.of()),
                context -> { throw new IllegalStateException("boom"); });

            var result = runtime.reload(ExtensionRuntimeConfig.explicit(List.of(broken)));

            assertFalse(result.applied());
            assertEquals(previous, runtime.catalog().contentHash());
            assertEquals(previous, result.currentCatalogHash());
        }
    }

    @Test
    void retainedContextIsClosedAfterContributionReturns() {
        var retained = new AtomicReference<ExtensionContext>();
        RegelsuchePlugin plugin = pluginWithBody(
            descriptor("retainer", "1", List.of()), retained::set);

        try (var ignored = ExtensionRuntime.open(
                ExtensionRuntimeConfig.explicit(List.of(plugin)))) {
            assertThrows(IllegalStateException.class,
                () -> retained.get().contribute(
                    POINT, ExtensionDescriptor.named("late"), (Runnable) () -> {}));
        }
    }

    @Test
    void contributionContextRejectsForeignThread() {
        var observed = new AtomicReference<Throwable>();
        RegelsuchePlugin plugin = pluginWithBody(
            descriptor("threaded", "1", List.of()),
            context -> {
                Thread thread = Thread.ofPlatform().start(() -> {
                    try {
                        context.contribute(
                            POINT, ExtensionDescriptor.named("foreign"), (Runnable) () -> {});
                    } catch (Throwable failure) {
                        observed.set(failure);
                    }
                });
                try {
                    thread.join();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });

        try (var ignored = ExtensionRuntime.open(
                ExtensionRuntimeConfig.explicit(List.of(plugin)))) {
            assertInstanceOf(IllegalStateException.class, observed.get());
        }
    }

    @Test
    void incompatibleApiAndNewerCoreFailClosed() {
        RegelsuchePlugin wrongApi = pluginWithBody(
            new PluginDescriptor(
                "wrong-api", "wrong-api", "1", "999", "1.0.0",
                Set.of(), List.of(), "fixture"),
            context -> {});
        RegelsuchePlugin newerCore = pluginWithBody(
            new PluginDescriptor(
                "newer-core", "newer-core", "1", ExtensionApi.VERSION, "9.0.0",
                Set.of(), List.of(), "fixture"),
            context -> {});

        assertThrows(IllegalArgumentException.class,
            () -> ExtensionRuntime.open(ExtensionRuntimeConfig.explicit(List.of(wrongApi))));
        assertThrows(IllegalArgumentException.class,
            () -> ExtensionRuntime.open(ExtensionRuntimeConfig.explicit(List.of(newerCore))));
    }

    @Test
    void requiredOptionalAndExactVersionDependenciesAreChecked() {
        RegelsuchePlugin provider = pluginWithBody(
            descriptor("provider", "2", List.of()), context -> {});
        RegelsuchePlugin required = pluginWithBody(
            descriptor("required", "1", List.of(
                new PluginDependency("provider", "2", false),
                new PluginDependency("optional-missing", "any", true))),
            context -> {});

        try (var runtime = ExtensionRuntime.open(
                ExtensionRuntimeConfig.explicit(List.of(required, provider)))) {
            assertTrue(runtime.catalog().registrations(POINT).isEmpty());
        }

        RegelsuchePlugin missing = pluginWithBody(
            descriptor("missing", "1", List.of(
                new PluginDependency("absent", "any", false))),
            context -> {});
        assertThrows(IllegalArgumentException.class,
            () -> ExtensionRuntime.open(ExtensionRuntimeConfig.explicit(List.of(missing))));

        RegelsuchePlugin unsupported = pluginWithBody(
            descriptor("unsupported", "1", List.of(
                new PluginDependency("provider", ">=2", false))),
            context -> {});
        assertThrows(IllegalArgumentException.class,
            () -> ExtensionRuntime.open(
                ExtensionRuntimeConfig.explicit(List.of(unsupported, provider))));
    }

    @Test
    void rejectedExternalArtifactIsNeverInstantiated(@TempDir Path temp) throws Exception {
        Path sourceJar = TestPluginJar.buildPlugin(temp.resolve("source"));
        Path sentinel = temp.resolve("loaded.txt");
        System.setProperty("regelsuche.fixture.sentinel", sentinel.toString());
        try {
            var config = externalConfig(List.of(sourceJar), jar -> {
                throw new SecurityException("rejected");
            });

            assertThrows(SecurityException.class, () -> ExtensionRuntime.open(config));
            assertFalse(Files.exists(sentinel));
        } finally {
            System.clearProperty("regelsuche.fixture.sentinel");
        }
    }

    @Test
    void sourceMutationAfterAdmissionCannotChangeLoadedBytes(@TempDir Path temp)
            throws Exception {
        Path sourceJar = TestPluginJar.buildPlugin(temp.resolve("source"));
        byte[] admittedBytes = Files.readAllBytes(sourceJar);
        String admittedHash = AdmittedPluginArtifact.sha256(admittedBytes);
        var admissionCalls = new AtomicInteger();
        PluginArtifactAdmission admission = source -> {
            admissionCalls.incrementAndGet();
            byte[] snapshot = Files.readAllBytes(source);
            Files.writeString(source, "not-a-jar", StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
            return AdmittedPluginArtifact.of(snapshot, admittedHash, "", source.toString());
        };

        try (var runtime = ExtensionRuntime.open(
                externalConfig(List.of(sourceJar), admission))) {
            var registration = runtime.catalog().find(EXTERNAL_POINT, "run").orElseThrow();
            assertEquals(1, admissionCalls.get());
            assertEquals(admittedHash, registration.origin().artifactSha256());
            assertEquals("external-fixture", registration.origin().sourceId());
        }
    }

    @Test
    void admittedJarsShareOneLoaderForCrossJarDependencies(@TempDir Path temp)
            throws Exception {
        Path libraryJar = TestPluginJar.buildLibrary(temp.resolve("library"));
        Path pluginJar = TestPluginJar.buildPluginUsingLibrary(temp.resolve("plugin"), libraryJar);
        PluginArtifactAdmission admission = source -> {
            byte[] bytes = Files.readAllBytes(source);
            return AdmittedPluginArtifact.of(
                bytes, AdmittedPluginArtifact.sha256(bytes), "", source.toString());
        };

        try (var runtime = ExtensionRuntime.open(
                externalConfig(List.of(libraryJar, pluginJar), admission))) {
            var registration = runtime.catalog().find(EXTERNAL_POINT, "run").orElseThrow();
            assertEquals("from-library", registration.descriptor().name());
        }
    }

    @Test
    void artifactSnapshotDefensivelyCopiesInputAndOutput() {
        byte[] source = "bytes".getBytes(StandardCharsets.UTF_8);
        String hash = AdmittedPluginArtifact.sha256(source);
        var admitted = AdmittedPluginArtifact.of(source, hash, "", "fixture");
        source[0] = 'X';
        byte[] returned = admitted.admittedBytes();
        returned[1] = 'Y';

        assertEquals(hash, admitted.artifactSha256());
        assertEquals("bytes", new String(admitted.admittedBytes(), StandardCharsets.UTF_8));
    }

    private static ExtensionRuntimeConfig externalConfig(
        List<Path> jars,
        PluginArtifactAdmission admission
    ) {
        return new ExtensionRuntimeConfig(
            ExtensionApi.CORE_COMPATIBILITY_VERSION,
            false,
            List.of(),
            jars,
            admission,
            ExtensionRuntimeTest.class.getClassLoader());
    }

    private static RegelsuchePlugin plugin(String id, String contributionId) {
        return pluginWithBody(
            descriptor(id, "1", List.of()),
            context -> context.contribute(
                POINT, ExtensionDescriptor.named(contributionId), (Runnable) () -> {}));
    }

    private static PluginDescriptor descriptor(
        String id,
        String version,
        List<PluginDependency> dependencies
    ) {
        return new PluginDescriptor(
            id, id, version, ExtensionApi.VERSION, ExtensionApi.CORE_COMPATIBILITY_VERSION,
            Set.of(), dependencies, "fixture");
    }

    private static RegelsuchePlugin pluginWithBody(
        PluginDescriptor descriptor,
        Consumer<ExtensionContext> body
    ) {
        return new RegelsuchePlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public void contribute(ExtensionContext context) {
                body.accept(context);
            }
        };
    }
}
