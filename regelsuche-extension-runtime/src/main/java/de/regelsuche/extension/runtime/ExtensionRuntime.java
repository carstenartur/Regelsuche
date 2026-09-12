package de.regelsuche.extension.runtime;

import de.regelsuche.api.StableApi;
import de.regelsuche.extension.ExtensionApi;
import de.regelsuche.extension.ExtensionCatalog;
import de.regelsuche.extension.ExtensionCatalogs;
import de.regelsuche.extension.ExtensionContext;
import de.regelsuche.extension.ExtensionDescriptor;
import de.regelsuche.extension.ExtensionOrigin;
import de.regelsuche.extension.ExtensionPoint;
import de.regelsuche.extension.PluginDependency;
import de.regelsuche.extension.PluginDescriptor;
import de.regelsuche.extension.RegelsuchePlugin;
import de.regelsuche.extension.RegisteredExtension;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Headless, transactional runtime for generic Regelsuche extensions.
 *
 * <p>External artifacts are fully admitted as immutable byte snapshots and
 * privately restaged before any external plugin classloader is created.</p>
 */
@StableApi(since = "2")
public final class ExtensionRuntime implements AutoCloseable {
    private static final String SERVICE_RESOURCE =
        "META-INF/services/" + RegelsuchePlugin.class.getName();

    private Snapshot active;
    private boolean closed;

    private ExtensionRuntime(Snapshot active) {
        this.active = active;
    }

    /** Builds and validates the initial complete catalog snapshot. */
    public static ExtensionRuntime open(ExtensionRuntimeConfig config) {
        return new ExtensionRuntime(build(Objects.requireNonNull(config, "config")));
    }

    /** Returns the current immutable catalog snapshot. */
    public synchronized ExtensionCatalog catalog() {
        ensureOpen();
        return active.catalog();
    }

    /**
     * Builds a complete candidate snapshot and swaps atomically only on success.
     *
     * <p>A failed candidate leaves the previous catalog and resources active.</p>
     */
    public synchronized CatalogReloadResult reload(ExtensionRuntimeConfig config) {
        ensureOpen();
        String previousHash = active.catalog().contentHash();
        try {
            Snapshot candidate = build(Objects.requireNonNull(config, "config"));
            Snapshot previous = active;
            active = candidate;
            previous.close();
            return new CatalogReloadResult(
                true, previousHash, active.catalog().contentHash(), "");
        } catch (RuntimeException failure) {
            return new CatalogReloadResult(
                false,
                previousHash,
                active.catalog().contentHash(),
                failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()));
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            active.close();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("extension runtime is closed");
        }
    }

    private static Snapshot build(ExtensionRuntimeConfig config) {
        ExternalResources external = null;
        boolean success = false;
        try {
            // Validate the host declaration even for an empty catalog, before plugin loading.
            CoreCompatibilityVersion.parse(config.coreCompatibilityVersion());
            List<LoadedPlugin> loaded = new ArrayList<>();
            for (RegelsuchePlugin plugin : config.explicitPlugins()) {
                loaded.add(classpathPlugin(plugin, "explicit"));
            }
            if (config.loadClasspathPlugins()) {
                loaded.addAll(loadClasspathPlugins(config.parentClassLoader()));
            }
            if (!config.externalPluginJars().isEmpty()) {
                external = loadExternalPlugins(config);
                loaded.addAll(external.plugins());
            }

            List<LoadedPlugin> normalized = validateDescriptors(
                loaded, config.coreCompatibilityVersion());
            List<RegisteredExtension<?>> contributions = new ArrayList<>();
            for (LoadedPlugin loadedPlugin : normalized) {
                StagingContext context = new StagingContext(loadedPlugin.origin());
                try {
                    loadedPlugin.plugin().contribute(context);
                } finally {
                    context.freeze();
                }
                contributions.addAll(context.snapshot());
            }

            Snapshot snapshot = new Snapshot(ExtensionCatalogs.of(contributions), external);
            success = true;
            return snapshot;
        } catch (ServiceConfigurationError failure) {
            throw new IllegalStateException("failed to discover extension plugins", failure);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("failed to build extension catalog", failure);
        } finally {
            if (!success && external != null) {
                external.close();
            }
        }
    }

    private static List<LoadedPlugin> loadClasspathPlugins(ClassLoader classLoader) {
        List<LoadedPlugin> plugins = new ArrayList<>();
        for (ServiceLoader.Provider<RegelsuchePlugin> provider
                : ServiceLoader.load(RegelsuchePlugin.class, classLoader).stream().toList()) {
            RegelsuchePlugin plugin = provider.get();
            plugins.add(classpathPlugin(plugin, sourceReference(plugin.getClass())));
        }
        return plugins;
    }

    private static LoadedPlugin classpathPlugin(RegelsuchePlugin plugin, String reference) {
        Objects.requireNonNull(plugin, "plugin");
        PluginDescriptor descriptor = Objects.requireNonNull(
            plugin.descriptor(), "plugin descriptor");
        return new LoadedPlugin(
            plugin,
            descriptor,
            ExtensionOrigin.classpathPlugin(descriptor.id(), descriptor.version(), reference));
    }

    private static LoadedPlugin externalPlugin(
        RegelsuchePlugin plugin,
        ArtifactMetadata metadata
    ) {
        Objects.requireNonNull(plugin, "plugin");
        PluginDescriptor descriptor = Objects.requireNonNull(
            plugin.descriptor(), "plugin descriptor");
        return new LoadedPlugin(
            plugin,
            descriptor,
            ExtensionOrigin.externalPlugin(
                descriptor.id(),
                descriptor.version(),
                metadata.sourceReference(),
                metadata.artifactSha256(),
                metadata.trustEvidenceSha256()));
    }

    private static List<LoadedPlugin> validateDescriptors(
        List<LoadedPlugin> loaded,
        String coreCompatibilityVersion
    ) {
        Map<String, LoadedPlugin> byId = new HashMap<>();
        for (LoadedPlugin plugin : loaded) {
            PluginDescriptor descriptor = plugin.descriptor();
            if (!ExtensionApi.VERSION.equals(descriptor.apiVersion())) {
                throw new IllegalArgumentException(
                    "incompatible extension API for " + descriptor.id()
                        + ": requires " + descriptor.apiVersion()
                        + " but runtime provides " + ExtensionApi.VERSION);
            }
            if (isNewerVersion(descriptor.minimumCoreVersion(), coreCompatibilityVersion)) {
                throw new IllegalArgumentException(
                    "plugin requires newer core: " + descriptor.id()
                        + " requires " + descriptor.minimumCoreVersion()
                        + " but runtime provides " + coreCompatibilityVersion);
            }
            if (byId.putIfAbsent(descriptor.id(), plugin) != null) {
                throw new IllegalArgumentException("duplicate plugin id: " + descriptor.id());
            }
        }

        for (LoadedPlugin plugin : loaded) {
            for (PluginDependency dependency : plugin.descriptor().dependencies()) {
                String constraint = dependency.versionConstraint();
                if (!"any".equals(constraint) && !isExactVersionConstraint(constraint)) {
                    throw new IllegalArgumentException(
                        "unsupported plugin dependency constraint: " + constraint);
                }
                LoadedPlugin target = byId.get(dependency.pluginId());
                if (target == null) {
                    if (!dependency.optional()) {
                        throw new IllegalArgumentException(
                            "missing required plugin dependency: "
                                + plugin.descriptor().id() + " -> " + dependency.pluginId());
                    }
                    continue;
                }
                if ("any".equals(constraint)) {
                    continue;
                }
                if (!target.descriptor().version().equals(constraint)) {
                    throw new IllegalArgumentException(
                        "plugin dependency version mismatch: "
                            + plugin.descriptor().id() + " -> " + dependency.pluginId()
                            + " requires " + constraint
                            + " but found " + target.descriptor().version());
                }
            }
        }

        return loaded.stream()
            .sorted(Comparator.comparing(plugin -> plugin.descriptor().id()))
            .toList();
    }

    private static boolean isExactVersionConstraint(String value) {
        return value.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");
    }

    private static boolean isNewerVersion(String required, String available) {
        return CoreCompatibilityVersion.parse(required)
            .compareTo(CoreCompatibilityVersion.parse(available)) > 0;
    }

    private static ExternalResources loadExternalPlugins(ExtensionRuntimeConfig config)
            throws Exception {
        List<AdmittedPluginArtifact> admitted = new ArrayList<>();
        for (Path source : config.externalPluginJars()) {
            admitted.add(Objects.requireNonNull(
                config.artifactAdmission().admit(source), "admitted artifact"));
        }

        Path stagingDirectory = Files.createTempDirectory("regelsuche-extension-runtime-");
        URLClassLoader classLoader = null;
        try {
            Map<Path, ArtifactMetadata> metadataByPath = new HashMap<>();
            Map<String, Path> expectedProviders = new LinkedHashMap<>();
            List<URL> urls = new ArrayList<>();

            for (int index = 0; index < admitted.size(); index++) {
                AdmittedPluginArtifact artifact = admitted.get(index);
                Path staged = stagingDirectory.resolve(
                    String.format(Locale.ROOT, "plugin-%03d.jar", index));
                Files.write(
                    staged,
                    artifact.admittedBytes(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
                String stagedHash = AdmittedPluginArtifact.sha256(Files.readAllBytes(staged));
                if (!stagedHash.equals(artifact.artifactSha256())) {
                    throw new SecurityException("privately staged plugin hash mismatch");
                }
                Path canonicalPath = staged.toRealPath();
                metadataByPath.put(
                    canonicalPath,
                    new ArtifactMetadata(
                        artifact.artifactSha256(),
                        artifact.trustEvidenceSha256(),
                        artifact.sourceReference()));
                urls.add(canonicalPath.toUri().toURL());
                for (String providerName : serviceProviders(canonicalPath)) {
                    Path previous = expectedProviders.putIfAbsent(providerName, canonicalPath);
                    if (previous != null) {
                        throw new IllegalArgumentException(
                            "duplicate external provider class: " + providerName);
                    }
                }
            }

            classLoader = new URLClassLoader(
                urls.toArray(URL[]::new), config.parentClassLoader());
            Map<String, ServiceLoader.Provider<RegelsuchePlugin>> discovered = new HashMap<>();
            for (ServiceLoader.Provider<RegelsuchePlugin> provider
                    : ServiceLoader.load(RegelsuchePlugin.class, classLoader).stream().toList()) {
                if (expectedProviders.containsKey(provider.type().getName())) {
                    ServiceLoader.Provider<RegelsuchePlugin> previous = discovered.putIfAbsent(
                        provider.type().getName(), provider);
                    if (previous != null) {
                        throw new IllegalArgumentException(
                            "duplicate discovered external provider: " + provider.type().getName());
                    }
                }
            }

            List<LoadedPlugin> plugins = new ArrayList<>();
            for (Map.Entry<String, Path> expected : expectedProviders.entrySet()) {
                ServiceLoader.Provider<RegelsuchePlugin> provider = discovered.get(expected.getKey());
                if (provider == null) {
                    throw new IllegalArgumentException(
                        "external plugin provider not found: " + expected.getKey());
                }
                RegelsuchePlugin plugin = provider.get();
                Path codeSource = codeSource(plugin.getClass());
                if (!expected.getValue().equals(codeSource)) {
                    throw new SecurityException(
                        "external plugin code source does not match admitted artifact: "
                            + expected.getKey());
                }
                ArtifactMetadata metadata = metadataByPath.get(codeSource);
                if (metadata == null) {
                    throw new SecurityException(
                        "external plugin code source is not an admitted artifact: "
                            + expected.getKey());
                }
                plugins.add(externalPlugin(plugin, metadata));
            }
            return new ExternalResources(
                classLoader, stagingDirectory, List.copyOf(plugins));
        } catch (Exception | Error failure) {
            if (classLoader != null) {
                try {
                    classLoader.close();
                } catch (IOException ignored) {
                    // Preserve the original failure.
                }
            }
            deleteTree(stagingDirectory);
            throw failure;
        }
    }

    private static List<String> serviceProviders(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(SERVICE_RESOURCE);
            if (entry == null) {
                return List.of();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    file.getInputStream(entry), StandardCharsets.UTF_8))) {
                var names = new java.util.LinkedHashSet<String>();
                String line;
                while ((line = reader.readLine()) != null) {
                    String value = line.split("#", 2)[0].trim();
                    if (!value.isEmpty()) {
                        names.add(value);
                    }
                }
                return List.copyOf(names);
            }
        }
    }

    private static Path codeSource(Class<?> type) {
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null) {
                throw new SecurityException("plugin has no code source: " + type.getName());
            }
            return Path.of(source.getLocation().toURI()).toRealPath();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(
                "cannot resolve plugin code source: " + type.getName(), failure);
        }
    }

    private static String sourceReference(Class<?> type) {
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            return source == null ? "classpath" : source.getLocation().toString();
        } catch (RuntimeException failure) {
            return "classpath";
        }
    }

    private static void deleteTree(Path root) {
        if (root == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup after classloader close or failed staging.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup; no catalog identity depends on the temp directory.
        }
    }

    private record LoadedPlugin(
        RegelsuchePlugin plugin,
        PluginDescriptor descriptor,
        ExtensionOrigin origin
    ) {}

    private record ArtifactMetadata(
        String artifactSha256,
        String trustEvidenceSha256,
        String sourceReference
    ) {}

    private static final class StagingContext implements ExtensionContext {
        private final Thread owner = Thread.currentThread();
        private final ExtensionOrigin origin;
        private final List<RegisteredExtension<?>> staged = new ArrayList<>();
        private boolean open = true;

        StagingContext(ExtensionOrigin origin) {
            this.origin = origin;
        }

        @Override
        public synchronized <T> void contribute(
            ExtensionPoint<T> point,
            ExtensionDescriptor descriptor,
            T implementation
        ) {
            if (!open) {
                throw new IllegalStateException("extension contribution context is closed");
            }
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("extension contribution context is thread-confined");
            }
            staged.add(new RegisteredExtension<>(point, descriptor, implementation, origin));
        }

        synchronized void freeze() {
            open = false;
        }

        synchronized List<RegisteredExtension<?>> snapshot() {
            if (open) {
                throw new IllegalStateException(
                    "extension contribution context must be frozen before publication");
            }
            return List.copyOf(staged);
        }
    }

    private static final class ExternalResources implements AutoCloseable {
        private final URLClassLoader classLoader;
        private final Path stagingDirectory;
        private final List<LoadedPlugin> plugins;

        ExternalResources(
            URLClassLoader classLoader,
            Path stagingDirectory,
            List<LoadedPlugin> plugins
        ) {
            this.classLoader = classLoader;
            this.stagingDirectory = stagingDirectory;
            this.plugins = plugins;
        }

        List<LoadedPlugin> plugins() {
            return plugins;
        }

        @Override
        public void close() {
            try {
                classLoader.close();
            } catch (IOException ignored) {
                // Continue cleanup of private staged bytes.
            }
            deleteTree(stagingDirectory);
        }
    }

    private record Snapshot(
        ExtensionCatalog catalog,
        ExternalResources externalResources
    ) implements AutoCloseable {
        @Override
        public void close() {
            if (externalResources != null) {
                externalResources.close();
            }
        }
    }
}
