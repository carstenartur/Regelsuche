package de.regelsuche.extension.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.extension.ExtensionApi;
import de.regelsuche.extension.ExtensionPoint;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/** Real JAR/classloader probes; the sentinel observes class initialization, not a mock. */
@Isolated("The existing dynamic fixture observes a process-wide sentinel property")
class ExtensionRuntimeAdmissionTest {
    private static final String SENTINEL_PROPERTY = "regelsuche.fixture.sentinel";
    private static final ExtensionPoint<Runnable> POINT =
        ExtensionPoint.of("fixture.external", Runnable.class);

    @Test
    void manifestCannotAddAnUnadmittedDependency(@TempDir Path temp) throws Exception {
        Path library = TestPluginJar.buildLibrary(temp.resolve("library"));
        Path plugin = TestPluginJar.buildPluginUsingLibrary(temp.resolve("plugin"), library);
        Path withManifest = withClassPath(plugin, temp.resolve("with-manifest.jar"),
            library.toUri().toASCIIString());
        var admissions = new AtomicInteger();
        assertThrows(SecurityException.class, () -> {
            try (var ignored = ExtensionRuntime.open(config(
                    List.of(withManifest), getClass().getClassLoader(), admissions))) {
            }
        });
        assertEquals(1, admissions.get());
    }

    @Test
    void libraryManifestIsRejectedBeforeAnyProviderInitialization(@TempDir Path temp)
            throws Exception {
        Path plugin = TestPluginJar.buildPlugin(temp.resolve("plugin"));
        Path library = TestPluginJar.buildLibrary(temp.resolve("library"));
        Path withManifest = withClassPath(library, temp.resolve("library-with-manifest.jar"),
            "unadmitted.jar");
        Path sentinel = temp.resolve("initialized.txt");
        String previous = System.setProperty(SENTINEL_PROPERTY, sentinel.toString());
        try {
            var admissions = new AtomicInteger();
            assertThrows(SecurityException.class, () -> {
                try (var ignored = ExtensionRuntime.open(config(
                        List.of(plugin, withManifest), getClass().getClassLoader(), admissions))) {
                }
            });
            assertEquals(2, admissions.get());
            assertFalse(Files.exists(sentinel));
        } finally {
            restoreSentinel(previous);
        }
    }

    @Test
    void parentShadowedProviderIsRejectedBeforeInitialization(@TempDir Path temp)
            throws Exception {
        Path parentJar = TestPluginJar.buildPlugin(temp.resolve("parent"));
        Path externalJar = TestPluginJar.buildPlugin(temp.resolve("external"));
        Path sentinel = temp.resolve("parent-initialized.txt");
        String previous = System.setProperty(SENTINEL_PROPERTY, sentinel.toString());
        try (var parent = new URLClassLoader(new URL[] { parentJar.toUri().toURL() },
                getClass().getClassLoader())) {
            var admissions = new AtomicInteger();
            assertThrows(SecurityException.class, () -> {
                try (var ignored = ExtensionRuntime.open(config(
                        List.of(externalJar), parent, admissions))) {
                }
            });
            assertEquals(1, admissions.get());
            assertFalse(Files.exists(sentinel));
        } finally {
            restoreSentinel(previous);
        }
    }

    @Test
    void explicitlyAdmittedCrossJarDependencyRemainsUsable(@TempDir Path temp)
            throws Exception {
        Path library = TestPluginJar.buildLibrary(temp.resolve("library"));
        Path plugin = TestPluginJar.buildPluginUsingLibrary(temp.resolve("plugin"), library);
        var admissions = new AtomicInteger();
        try (var runtime = ExtensionRuntime.open(config(
                List.of(plugin, library), getClass().getClassLoader(), admissions))) {
            var contribution = runtime.catalog().find(POINT, "run").orElseThrow();
            assertEquals("from-library", contribution.descriptor().name());
            contribution.implementation().run();
            assertEquals(2, admissions.get());
        }
    }

    private static ExtensionRuntimeConfig config(
        List<Path> jars, ClassLoader parent, AtomicInteger admissions
    ) {
        return new ExtensionRuntimeConfig(ExtensionApi.CORE_COMPATIBILITY_VERSION,
            false, List.of(), jars, source -> {
                admissions.incrementAndGet();
                byte[] bytes = Files.readAllBytes(source);
                return AdmittedPluginArtifact.of(bytes, AdmittedPluginArtifact.sha256(bytes),
                    "", source.toString());
            }, parent);
    }

    private static Path withClassPath(Path source, Path output, String classPath)
            throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
        try (var input = new JarFile(source.toFile());
                var jar = new JarOutputStream(Files.newOutputStream(output), manifest)) {
            for (JarEntry entry : input.stream().toList()) {
                if (JarFile.MANIFEST_NAME.equalsIgnoreCase(entry.getName())) {
                    continue;
                }
                jar.putNextEntry(new JarEntry(entry.getName()));
                try (var stream = input.getInputStream(entry)) {
                    stream.transferTo(jar);
                }
                jar.closeEntry();
            }
        }
        return output;
    }

    private static void restoreSentinel(String previous) {
        if (previous == null) {
            System.clearProperty(SENTINEL_PROPERTY);
        } else {
            System.setProperty(SENTINEL_PROPERTY, previous);
        }
    }
}
