package de.regelsuche.extension.runtime;

import de.regelsuche.api.StableApi;
import de.regelsuche.extension.ExtensionApi;
import de.regelsuche.extension.RegelsuchePlugin;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Immutable configuration for one extension-runtime snapshot build. */
@StableApi(since = "2")
public record ExtensionRuntimeConfig(
    String coreCompatibilityVersion,
    boolean loadClasspathPlugins,
    List<RegelsuchePlugin> explicitPlugins,
    List<Path> externalPluginJars,
    PluginArtifactAdmission artifactAdmission,
    ClassLoader parentClassLoader
) {
    public ExtensionRuntimeConfig {
        if (coreCompatibilityVersion == null || coreCompatibilityVersion.isBlank()) {
            throw new IllegalArgumentException("coreCompatibilityVersion must not be blank");
        }
        coreCompatibilityVersion = coreCompatibilityVersion.trim();
        explicitPlugins = List.copyOf(Objects.requireNonNull(explicitPlugins, "explicitPlugins"));
        externalPluginJars = List.copyOf(
            Objects.requireNonNull(externalPluginJars, "externalPluginJars"));
        for (Path path : externalPluginJars) {
            Objects.requireNonNull(path, "externalPluginJar");
        }
        Objects.requireNonNull(artifactAdmission, "artifactAdmission");
        parentClassLoader = parentClassLoader == null
            ? ExtensionRuntimeConfig.class.getClassLoader()
            : parentClassLoader;
    }

    /** Configuration containing only explicitly supplied trusted in-process plugins. */
    public static ExtensionRuntimeConfig explicit(List<RegelsuchePlugin> plugins) {
        return new ExtensionRuntimeConfig(
            ExtensionApi.CORE_COMPATIBILITY_VERSION,
            false,
            plugins,
            List.of(),
            source -> {
                throw new IllegalStateException("external artifact admission is not configured");
            },
            ExtensionRuntimeConfig.class.getClassLoader());
    }

    /** Configuration that discovers generic plugins visible to the supplied class loader. */
    public static ExtensionRuntimeConfig classpath(ClassLoader classLoader) {
        return new ExtensionRuntimeConfig(
            ExtensionApi.CORE_COMPATIBILITY_VERSION,
            true,
            List.of(),
            List.of(),
            source -> {
                throw new IllegalStateException("external artifact admission is not configured");
            },
            classLoader);
    }
}
