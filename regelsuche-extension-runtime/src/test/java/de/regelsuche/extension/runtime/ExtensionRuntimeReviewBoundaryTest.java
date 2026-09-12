package de.regelsuche.extension.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.api.IncubatingApi;
import de.regelsuche.api.StableApi;
import de.regelsuche.api.UnexportedHostApiFixture;
import de.regelsuche.extension.ExtensionApi;
import de.regelsuche.extension.ExtensionContext;
import de.regelsuche.extension.ExtensionDescriptor;
import de.regelsuche.extension.ExtensionPoint;
import de.regelsuche.extension.PluginDescriptor;
import de.regelsuche.extension.RegelsuchePlugin;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExtensionRuntimeReviewBoundaryTest {
    private static final ExtensionPoint<Runnable> POINT =
        ExtensionPoint.of("review.runnable", Runnable.class);
    private static final String SERVICE =
        "META-INF/services/" + RegelsuchePlugin.class.getName();

    @Test
    void lifecycleAnnotationsDoNotExportTheirWholeHostPackage() throws Exception {
        try (var loader = new AdmittedPluginClassLoader(new URL[0],
                getClass().getClassLoader(), Set.of())) {
            assertSame(StableApi.class, loader.loadClass(StableApi.class.getName()));
            assertSame(IncubatingApi.class, loader.loadClass(IncubatingApi.class.getName()));
            assertThrows(SecurityException.class,
                () -> loader.loadClass(UnexportedHostApiFixture.class.getName()));
        }
        try (var loader = new AdmittedPluginClassLoader(new URL[0],
                getClass().getClassLoader(), Set.of(UnexportedHostApiFixture.class))) {
            assertSame(UnexportedHostApiFixture.class,
                loader.loadClass(UnexportedHostApiFixture.class.getName()));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reentrantLifecycleMutationRejectsCandidateAndKeepsPublishedCatalog(boolean close) {
        var initial = ExtensionRuntimeConfig.explicit(List.of(plugin("initial", () -> {})));
        try (var runtime = ExtensionRuntime.open(initial)) {
            var previous = runtime.catalog();
            var callback = plugin("callback", () -> {
                if (close) runtime.close();
                else runtime.reload(ExtensionRuntimeConfig.explicit(
                    List.of(plugin("nested", () -> {}))));
            });

            var result = runtime.reload(ExtensionRuntimeConfig.explicit(List.of(callback)));

            assertFalse(result.applied());
            assertSame(previous, runtime.catalog());
            assertEquals(previous.contentHash(), result.previousCatalogHash());
            assertEquals(previous.contentHash(), result.currentCatalogHash());
            assertTrue(result.diagnostic().contains("reentrant"));
            assertDoesNotThrow(previous.find(POINT, "initial").orElseThrow().implementation()::run);
            assertTrue(runtime.reload(ExtensionRuntimeConfig.explicit(
                List.of(plugin("replacement", () -> {})))).applied());
            assertTrue(runtime.catalog().find(POINT, "replacement").isPresent());
        }
    }

    @Test
    void caughtReentryDoesNotPreventOtherwiseValidOuterPublication() {
        try (var runtime = ExtensionRuntime.open(ExtensionRuntimeConfig.explicit(List.of()))) {
            var callback = plugin("callback", () ->
                assertThrows(IllegalStateException.class, runtime::close));

            assertTrue(runtime.reload(ExtensionRuntimeConfig.explicit(List.of(callback))).applied());
            assertTrue(runtime.catalog().find(POINT, "callback").isPresent());
        }
    }

    @Test
    void providerMayResideInAnotherAdmittedJarWithoutLosingItsOrigin(@TempDir Path root)
            throws Exception {
        Path original = TestPluginJar.buildPlugin(root.resolve("original"));
        Path descriptor = root.resolve("descriptor.jar");
        Path implementation = root.resolve("implementation.jar");
        try (var input = new JarFile(original.toFile());
             var descriptorOut = new JarOutputStream(Files.newOutputStream(descriptor));
             var implementationOut = new JarOutputStream(Files.newOutputStream(implementation))) {
            for (var entry : input.stream().filter(e -> !e.isDirectory()).toList()) {
                var target = SERVICE.equals(entry.getName()) ? descriptorOut : implementationOut;
                target.putNextEntry(new JarEntry(entry.getName()));
                try (var content = input.getInputStream(entry)) {
                    content.transferTo(target);
                }
                target.closeEntry();
            }
        }

        try (var runtime = assertDoesNotThrow(() -> ExtensionRuntime.open(
                external(List.of(descriptor, implementation))))) {
            var contribution = runtime.catalog().find(
                ExtensionPoint.of("fixture.external", Runnable.class), "run").orElseThrow();
            assertEquals(AdmittedPluginArtifact.sha256(Files.readAllBytes(implementation)),
                contribution.origin().artifactSha256());
            assertEquals(implementation.toString(), contribution.origin().sourceReference());
        }
        // The class must still be admitted; a descriptor alone cannot authorize it.
        assertThrows(RuntimeException.class,
            () -> ExtensionRuntime.open(external(List.of(descriptor))));
        // Retain duplicate-provider rejection even across separate descriptor artifacts.
        assertThrows(RuntimeException.class,
            () -> ExtensionRuntime.open(external(List.of(descriptor, original))));
    }

    private static ExtensionRuntimeConfig external(List<Path> artifacts) {
        return new ExtensionRuntimeConfig(ExtensionApi.CORE_COMPATIBILITY_VERSION,
            false, List.of(), artifacts, source -> {
                byte[] bytes = Files.readAllBytes(source);
                return AdmittedPluginArtifact.of(bytes, AdmittedPluginArtifact.sha256(bytes),
                    "", source.toString());
            }, ExtensionRuntimeReviewBoundaryTest.class.getClassLoader());
    }

    private static RegelsuchePlugin plugin(String id, Runnable callback) {
        return new RegelsuchePlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor(id, id, "1", ExtensionApi.VERSION,
                    ExtensionApi.CORE_COMPATIBILITY_VERSION, Set.of(), List.of(), "test");
            }
            @Override
            public void contribute(ExtensionContext context) {
                callback.run();
                context.contribute(POINT, ExtensionDescriptor.named(id), (Runnable) () -> {});
            }
        };
    }
}
