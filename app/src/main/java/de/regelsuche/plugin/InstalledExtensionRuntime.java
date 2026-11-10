package de.regelsuche.plugin;

import de.regelsuche.extension.ExtensionApi;
import de.regelsuche.extension.ExtensionCatalog;
import de.regelsuche.extension.runtime.AdmittedPluginArtifact;
import de.regelsuche.extension.runtime.ExtensionRuntime;
import de.regelsuche.extension.runtime.ExtensionRuntimeConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * App-owned bridge from a verified installed closure to the generic SPI v2.
 * Own one instance per host unit of work and close it after all contributions
 * finish. Reopen to observe updates/removals; no stale-state fallback is used.
 * Authenticated Java has process privileges: this adapter is not a sandbox.
 */
public final class InstalledExtensionRuntime implements AutoCloseable {
    private final ExtensionRuntime runtime;
    private final Optional<PluginInstallationEvidence> installation;

    private InstalledExtensionRuntime(ExtensionRuntime runtime, Optional<PluginInstallationEvidence> installation) {
        this.runtime = runtime;
        this.installation = installation;
    }

    public static InstalledExtensionRuntime open(PluginDistributionClient client) throws IOException {
        return open(client, Options.defaults());
    }

    public static InstalledExtensionRuntime open(PluginDistributionClient client, Options options) throws IOException {
        Objects.requireNonNull(options, "options");
        return open(Objects.requireNonNull(client, "client").installedSnapshot(), options);
    }

    public static InstalledExtensionRuntime open(PluginDistributionTransactions transactions) throws IOException {
        return open(transactions, Options.defaults());
    }

    public static InstalledExtensionRuntime open(PluginDistributionTransactions transactions, Options options)
            throws IOException {
        Objects.requireNonNull(options, "options");
        return open(Objects.requireNonNull(transactions, "transactions").installedSnapshot(), options);
    }

    private static InstalledExtensionRuntime open(Optional<InstalledPluginSnapshot> selected, Options options) {
        var admissions = new LinkedHashMap<Path, AdmittedPluginArtifact>();
        if (selected.isPresent()) {
            var snapshot = selected.orElseThrow();
            snapshot.resolution().ifPresent(resolution -> {
                if (!ExtensionApi.VERSION.equals(resolution.request().apiVersion())
                        || !options.coreCompatibilityVersion().equals(resolution.request().coreVersion())) {
                    throw new SecurityException("installed closure was not resolved for this SPI/core version");
                }
            });
            for (var artifact : snapshot.artifacts()) {
                if (!ExtensionApi.VERSION.equals(artifact.entry().apiVersion())
                        || !artifact.entry().supportsCore(options.coreCompatibilityVersion())) {
                    throw new SecurityException("signed artifact compatibility excludes this host");
                }
                String identity = artifact.evidence().identityHash();
                // This path is an admission-map selector only; it is never read.
                Path selector = Path.of("installed", identity.substring(7) + ".jar");
                admissions.put(selector, AdmittedPluginArtifact.of(artifact.bytes(), artifact.evidence().artifactSha256(),
                    snapshot.installation().contentHash(), "installed:" + snapshot.installation().contentHash() + "/" + identity));
            }
        }
        var config = new ExtensionRuntimeConfig(options.coreCompatibilityVersion(), false, List.of(),
            List.copyOf(admissions.keySet()), path -> {
                var admitted = admissions.get(path);
                if (admitted == null) throw new SecurityException("artifact is outside the selected installed closure");
                return admitted;
            }, options.parentClassLoader(), options.sharedHostTypes());
        return new InstalledExtensionRuntime(ExtensionRuntime.open(config), selected.map(InstalledPluginSnapshot::installation));
    }

    public ExtensionCatalog catalog() { return runtime.catalog(); }

    /** Immutable activation metadata remains available after close. */
    public Optional<PluginInstallationEvidence> installation() { return installation; }

    @Override public void close() { runtime.close(); }

    /** Explicit host contract. Exporting a type does not export its package. */
    public record Options(String coreCompatibilityVersion, ClassLoader parentClassLoader, Set<Class<?>> sharedHostTypes) {
        public Options {
            coreCompatibilityVersion = PluginArtifactIndex.requireVersion(coreCompatibilityVersion, "host core version");
            parentClassLoader = parentClassLoader == null ? InstalledExtensionRuntime.class.getClassLoader() : parentClassLoader;
            sharedHostTypes = Set.copyOf(Objects.requireNonNull(sharedHostTypes, "sharedHostTypes"));
        }

        public static Options defaults() {
            return new Options(ExtensionApi.CORE_COMPATIBILITY_VERSION, InstalledExtensionRuntime.class.getClassLoader(), Set.of());
        }
    }
}
