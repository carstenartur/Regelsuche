package de.regelsuche.extension.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.extension.ExtensionApi;
import de.regelsuche.extension.ExtensionCatalog;
import de.regelsuche.extension.ExtensionPoint;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtensionRuntimeLifecycleTest {
    private static final ExtensionPoint<Runnable> POINT =
        ExtensionPoint.of("fixture.external", Runnable.class);

    @Test
    void publishedSnapshotLoadsUnusedDependencyAfterSuccessfulReload(@TempDir Path temp)
            throws Exception {
        Path plugin = TestPluginJar.buildPlugin(temp.resolve("plugin"));
        Path library = TestPluginJar.buildLibrary(temp.resolve("library"));
        try (var runtime = ExtensionRuntime.open(config(plugin, library))) {
            var previous = runtime.catalog();
            String hash = previous.contentHash();
            ClassLoader loader = loader(previous);

            var result = runtime.reload(ExtensionRuntimeConfig.explicit(List.of()));

            assertTrue(result.applied());
            assertNotSame(previous, runtime.catalog());
            assertEquals(hash, previous.contentHash());
            // First use after reload: a cached library class cannot mask premature close.
            assertEquals("from-library", Class.forName("fixture.SharedValue", true, loader)
                .getMethod("value").invoke(null));
            previous.find(POINT, "run").orElseThrow().implementation().run();
        }
    }

    @Test
    void bothRetiredGenerationsRetainResourcesAfterFurtherReload(@TempDir Path temp)
            throws Exception {
        Path plugin = TestPluginJar.buildPlugin(temp.resolve("plugin"));
        Path library = TestPluginJar.buildLibrary(temp.resolve("library"));
        byte[] expected;
        try (var jar = new JarFile(library.toFile());
                var input = jar.getInputStream(jar.getJarEntry("fixture/SharedValue.class"))) {
            expected = input.readAllBytes();
        }
        try (var runtime = ExtensionRuntime.open(config(plugin, library))) {
            var first = loader(runtime.catalog());
            assertTrue(runtime.reload(config(plugin, library)).applied());
            var second = loader(runtime.catalog());
            assertNotSame(first, second);
            assertTrue(runtime.reload(ExtensionRuntimeConfig.explicit(List.of())).applied());

            for (ClassLoader loader : List.of(first, second)) {
                try (var input = loader.getResourceAsStream("fixture/SharedValue.class")) {
                    assertNotNull(input, "published generation lost its resource");
                    assertArrayEquals(expected, input.readAllBytes());
                }
            }
        }
    }

    @Test
    void rejectedReloadPreservesLazyExternalClassesAndResources(@TempDir Path temp)
            throws Exception {
        Path plugin = TestPluginJar.buildPlugin(temp.resolve("plugin"));
        Path library = TestPluginJar.buildLibrary(temp.resolve("library"));
        try (var runtime = ExtensionRuntime.open(config(plugin, library))) {
            var previous = runtime.catalog();
            var loader = loader(previous);
            var invalid = new ExtensionRuntimeConfig("invalid", false, List.of(), List.of(),
                source -> { throw new AssertionError("must reject before admission"); },
                getClass().getClassLoader());

            var result = runtime.reload(invalid);

            assertFalse(result.applied());
            assertSame(previous, runtime.catalog());
            assertEquals(previous.contentHash(), result.currentCatalogHash());
            assertEquals("from-library", Class.forName("fixture.SharedValue", true, loader)
                .getMethod("value").invoke(null));
            try (var input = loader.getResourceAsStream("fixture/SharedValue.class")) {
                assertNotNull(input);
                assertTrue(input.read() >= 0);
            }
        }
    }

    @Test
    void closeReleasesCurrentAndRetiredPrivateArtifactsButNotSourceJars(@TempDir Path temp)
            throws Exception {
        Path plugin = TestPluginJar.buildPlugin(temp.resolve("plugin"));
        Path library = TestPluginJar.buildLibrary(temp.resolve("library"));
        var staged = new ArrayList<Path>();
        var runtime = ExtensionRuntime.open(config(plugin, library));
        try {
            staged.addAll(stagedPaths(loader(runtime.catalog())));
            assertTrue(runtime.reload(config(plugin, library)).applied());
            staged.addAll(stagedPaths(loader(runtime.catalog())));
            assertTrue(staged.stream().allMatch(Files::isRegularFile));
        } finally {
            runtime.close();
            runtime.close();
        }
        assertTrue(staged.stream().noneMatch(Files::exists));
        assertTrue(Files.isRegularFile(plugin));
        assertTrue(Files.isRegularFile(library));
    }

    private static URLClassLoader loader(ExtensionCatalog catalog) {
        var implementation = catalog.find(POINT, "run").orElseThrow().implementation();
        return assertInstanceOf(URLClassLoader.class, implementation.getClass().getClassLoader());
    }

    private static List<Path> stagedPaths(URLClassLoader loader) throws Exception {
        var paths = new ArrayList<Path>();
        for (var url : loader.getURLs()) {
            paths.add(Path.of(url.toURI()));
        }
        return List.copyOf(paths);
    }

    private static ExtensionRuntimeConfig config(Path plugin, Path library) {
        return new ExtensionRuntimeConfig(ExtensionApi.CORE_COMPATIBILITY_VERSION,
            false, List.of(), List.of(plugin, library), source -> {
                byte[] bytes = Files.readAllBytes(source);
                return AdmittedPluginArtifact.of(bytes, AdmittedPluginArtifact.sha256(bytes),
                    "", source.toString());
            }, ExtensionRuntimeLifecycleTest.class.getClassLoader());
    }
}
