package de.regelsuche.extension.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.extension.ExtensionApi;
import de.regelsuche.extension.ExtensionContext;
import de.regelsuche.extension.ExtensionPoint;
import de.regelsuche.extension.PluginDescriptor;
import de.regelsuche.extension.RegelsuchePlugin;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Regression coverage for the external dependency and numeric-declaration boundaries. */
@Isolated("Uses the dynamic plugin fixture with a process-wide sentinel")
class ExtensionRuntimeBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", " 1.0.0", "1.0.0 ", "1.0.0\n", "1.0.0\u00a0"})
    void suppliedMinimumVersionIsNotSilentlyNormalized(String declaration) {
        var descriptor = descriptor(declaration);
        assertEquals(declaration, descriptor.minimumCoreVersion());
        assertThrows(IllegalArgumentException.class, () -> {
            try (var ignored = ExtensionRuntime.open(ExtensionRuntimeConfig.explicit(List.of(
                    plugin(descriptor))))) {
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {" 1.0.0", "1.0.0 ", "1.0.0\n", "1.0.0\t"})
    void hostVersionIsNotSilentlyNormalized(String declaration) {
        var config = new ExtensionRuntimeConfig(declaration, false, List.of(), List.of(),
            source -> { throw new AssertionError("no external admission expected"); }, null);
        assertEquals(declaration, config.coreCompatibilityVersion());
        assertThrows(IllegalArgumentException.class, () -> {
            try (var ignored = ExtensionRuntime.open(config)) {
            }
        });
    }

    @Test
    void omittedMinimumStillUsesDocumentedDefault() {
        assertEquals("0.0.0", descriptor(null).minimumCoreVersion());
        try (var ignored = ExtensionRuntime.open(ExtensionRuntimeConfig.explicit(
                List.of(plugin(descriptor(null)))))) {
        }
    }

    @Test
    void ambientParentLibraryCannotSatisfyExternalDependency(@TempDir Path temp)
            throws Exception {
        Path library = TestPluginJar.buildLibrary(temp.resolve("library"));
        Path pluginJar = TestPluginJar.buildPluginUsingLibrary(temp.resolve("plugin"), library);
        try (var parent = new URLClassLoader(new URL[] {library.toUri().toURL()},
                getClass().getClassLoader())) {
            assertThrows(SecurityException.class, () -> {
                try (var ignored = ExtensionRuntime.open(config(List.of(pluginJar), parent))) {
                }
            });
        }
    }

    @Test
    void parentCannotShadowAnExplicitlyAdmittedLibrary(@TempDir Path temp) throws Exception {
        Path library = TestPluginJar.buildLibrary(temp.resolve("library"));
        Path pluginJar = TestPluginJar.buildPluginUsingLibrary(temp.resolve("plugin"), library);
        try (var parent = new URLClassLoader(new URL[] {library.toUri().toURL()},
                getClass().getClassLoader())) {
            assertThrows(SecurityException.class, () -> {
                try (var ignored = ExtensionRuntime.open(config(List.of(pluginJar, library), parent))) {
                }
            });
        }
    }

    @Test
    void rejectedParentDependencyPreservesPublishedCatalog(@TempDir Path temp) throws Exception {
        Path library = TestPluginJar.buildLibrary(temp.resolve("library"));
        Path pluginJar = TestPluginJar.buildPluginUsingLibrary(temp.resolve("plugin"), library);
        try (var parent = new URLClassLoader(new URL[] {library.toUri().toURL()},
                getClass().getClassLoader());
                var runtime = ExtensionRuntime.open(ExtensionRuntimeConfig.explicit(List.of()))) {
            var before = runtime.catalog();
            assertFalse(runtime.reload(config(List.of(pluginJar), parent)).applied());
            assertSame(before, runtime.catalog());
        }
    }

    @Test
    void admittedLibraryAndPlatformContractsRemainUsable(@TempDir Path temp) throws Exception {
        Path library = TestPluginJar.buildLibrary(temp.resolve("library"));
        Path pluginJar = TestPluginJar.buildPluginUsingLibrary(temp.resolve("plugin"), library);
        try (var runtime = ExtensionRuntime.open(config(List.of(pluginJar, library),
                getClass().getClassLoader()))) {
            var registered = runtime.catalog().find(
                ExtensionPoint.of("fixture.external", Runnable.class), "run").orElseThrow();
            assertEquals("from-library", registered.descriptor().name());
            registered.implementation().run();
        }
    }

    @Test
    void explicitlyExportedHostTypeRemainsUsable(@TempDir Path temp) throws Exception {
        Path library = TestPluginJar.buildLibrary(temp.resolve("library"));
        Path pluginJar = TestPluginJar.buildPluginUsingLibrary(temp.resolve("plugin"), library);
        try (var parent = new URLClassLoader(new URL[] {library.toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> hostType = Class.forName("fixture.SharedValue", false, parent);
            var base = config(List.of(pluginJar), parent);
            var exported = new ExtensionRuntimeConfig(base.coreCompatibilityVersion(), false,
                List.of(), base.externalPluginJars(), base.artifactAdmission(), parent,
                Set.of(hostType));
            try (var runtime = ExtensionRuntime.open(exported)) {
                assertEquals("from-library", runtime.catalog().find(
                    ExtensionPoint.of("fixture.external", Runnable.class), "run")
                    .orElseThrow().descriptor().name());
            }
        }
    }

    @Test
    void exportedTypeDoesNotExportSiblingClassesOrResources(@TempDir Path temp) throws Exception {
        Path library = TestPluginJar.buildLibrary(temp.resolve("library"));
        Path pluginJar = TestPluginJar.buildPlugin(temp.resolve("plugin"));
        try (var parent = new URLClassLoader(
                new URL[] {library.toUri().toURL(), pluginJar.toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> hostType = Class.forName("fixture.SharedValue", false, parent);
            try (var loader = new AdmittedPluginClassLoader(new URL[0], parent, Set.of(hostType))) {
                assertSame(hostType, loader.loadClass("fixture.SharedValue"));
                assertSame(java.sql.Driver.class, loader.loadClass("java.sql.Driver"));
                assertThrows(SecurityException.class, () -> loader.loadClass("fixture.ExternalPlugin"));
                assertNull(loader.getResource("fixture/SharedValue.class"));
                assertFalse(loader.getResources(
                    "META-INF/services/de.regelsuche.extension.RegelsuchePlugin").hasMoreElements());
            }
        }
    }

    @Test
    void nonExportedRuntimeImplementationIsNotPartOfTheHostApi(@TempDir Path temp) throws Exception {
        try (var loader = new AdmittedPluginClassLoader(new URL[0], getClass().getClassLoader(),
                Set.of())) {
            assertSame(RegelsuchePlugin.class, loader.loadClass(RegelsuchePlugin.class.getName()));
            assertSame(de.regelsuche.api.StableApi.class,
                loader.loadClass(de.regelsuche.api.StableApi.class.getName()));
            assertThrows(SecurityException.class,
                () -> loader.loadClass(ExtensionRuntime.class.getName()));
        }
    }

    @Test
    void parentShadowedProviderIsRejectedBeforeInitialization(@TempDir Path temp) throws Exception {
        Path parentJar = TestPluginJar.buildPlugin(temp.resolve("parent"));
        Path externalJar = TestPluginJar.buildPlugin(temp.resolve("external"));
        Path sentinel = temp.resolve("initialized.txt");
        String property = "regelsuche.fixture.sentinel";
        String previous = System.setProperty(property, sentinel.toString());
        try (var parent = new URLClassLoader(new URL[] {parentJar.toUri().toURL()},
                getClass().getClassLoader())) {
            assertThrows(SecurityException.class, () -> {
                try (var ignored = ExtensionRuntime.open(config(List.of(externalJar), parent))) {
                }
            });
            assertFalse(Files.exists(sentinel));
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    private static ExtensionRuntimeConfig config(List<Path> jars, ClassLoader parent) {
        return new ExtensionRuntimeConfig(ExtensionApi.CORE_COMPATIBILITY_VERSION,
            false, List.of(), jars, source -> {
                byte[] bytes = Files.readAllBytes(source);
                return AdmittedPluginArtifact.of(bytes, AdmittedPluginArtifact.sha256(bytes),
                    "", source.toString());
            }, parent);
    }

    private static PluginDescriptor descriptor(String minimum) {
        return new PluginDescriptor("boundary", "Boundary", "1", ExtensionApi.VERSION,
            minimum, Set.of(), List.of(), "test");
    }

    private static RegelsuchePlugin plugin(PluginDescriptor descriptor) {
        return new RegelsuchePlugin() {
            @Override public PluginDescriptor descriptor() { return descriptor; }
            @Override public void contribute(ExtensionContext context) { }
        };
    }
}
