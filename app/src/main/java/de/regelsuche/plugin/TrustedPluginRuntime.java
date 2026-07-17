package de.regelsuche.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Opt-in secure entry point for external plugins.
 *
 * <p>Artifacts are verified and snapshotted before the existing
 * {@link PluginRuntime} sees a JAR. Under {@link PluginTrustPolicy#REQUIRE_VERIFIED}
 * no unsigned, tampered, unknown or revoked artifact enters a class loader.</p>
 */
public final class TrustedPluginRuntime implements AutoCloseable {
    private final PluginRuntimeConfig sourceConfig;
    private final PluginArtifactTrustConfig trustConfig;

    private volatile PluginRuntime runtime;
    private volatile PluginArtifactGate.GateResult gateResult;
    private Path stagingDirectory;

    private TrustedPluginRuntime(
        PluginRuntimeConfig sourceConfig,
        PluginArtifactTrustConfig trustConfig
    ) {
        this.sourceConfig = Objects.requireNonNull(sourceConfig, "sourceConfig");
        this.trustConfig = Objects.requireNonNull(trustConfig, "trustConfig");
        reload();
    }

    public static TrustedPluginRuntime open(
        PluginRuntimeConfig sourceConfig,
        PluginArtifactTrustConfig trustConfig
    ) {
        return new TrustedPluginRuntime(sourceConfig, trustConfig);
    }

    /**
     * Replaces the active runtime only after a new trust decision and staged
     * runtime have been built successfully. A failed reload leaves the wrapper
     * closed rather than exposing stale trust evidence for a closed runtime.
     */
    public synchronized void reload() {
        closeCurrentRuntime();
        cleanupStagingDirectory();
        gateResult = null;

        try {
            PluginTrustStore trustStore = PluginTrustStore.load(trustConfig.trustStorePath());
            stagingDirectory = Files.createTempDirectory("regelsuche-trusted-plugins-");
            PluginArtifactGate.GateResult nextGateResult = new PluginArtifactGate(
                trustStore,
                trustConfig.policy()
            ).materialize(sourceConfig.pluginsDirectory(), stagingDirectory);
            PluginRuntimeConfig stagedConfig = new PluginRuntimeConfig(
                stagingDirectory,
                sourceConfig.rulesDirectory(),
                sourceConfig.loadClasspathPlugins(),
                sourceConfig.disabledPluginIds(),
                sourceConfig.disabledRuleIds(),
                sourceConfig.activeProfile()
            );
            PluginRuntime nextRuntime = new PluginRuntime(stagedConfig);
            gateResult = nextGateResult;
            runtime = nextRuntime;
        } catch (RuntimeException | IOException exception) {
            closeCurrentRuntime();
            cleanupStagingDirectory();
            gateResult = null;
            throw new IllegalStateException("Unable to initialize trusted plugin runtime", exception);
        }
    }

    public PluginRuntime runtime() {
        PluginRuntime current = runtime;
        if (current == null) {
            throw new IllegalStateException("trusted plugin runtime is closed");
        }
        return current;
    }

    public PluginArtifactGate.GateResult gateResult() {
        PluginArtifactGate.GateResult current = gateResult;
        if (current == null) {
            throw new IllegalStateException("trusted plugin runtime is closed");
        }
        return current;
    }

    public List<PluginRuntime.LoadedPlugin> loadedPlugins() {
        return runtime().loadedPlugins();
    }

    /** Existing runtime diagnostics plus deterministic artifact trust decisions. */
    public List<PluginRuntime.RuntimeDiagnostic> diagnostics() {
        List<PluginRuntime.RuntimeDiagnostic> combined = new ArrayList<>(runtime().diagnostics());
        for (PluginArtifactVerification verification : gateResult().verifications()) {
            combined.add(new PluginRuntime.RuntimeDiagnostic(
                "plugin-artifact:" + verification.artifactFileName(),
                "trustStatus=" + verification.status().name()
                    + ", artifactSha256=" + verification.artifactSha256()
                    + ", publisherId=" + verification.publisherId()
                    + ", keyId=" + verification.keyId()
                    + ", admitted=" + verification.permittedBy(trustConfig.policy())
            ));
        }
        return List.copyOf(combined);
    }

    @Override
    public synchronized void close() {
        closeCurrentRuntime();
        cleanupStagingDirectory();
        gateResult = null;
    }

    private void closeCurrentRuntime() {
        PluginRuntime current = runtime;
        runtime = null;
        if (current != null) {
            current.close();
        }
    }

    private void cleanupStagingDirectory() {
        if (stagingDirectory == null || !Files.exists(stagingDirectory)) {
            stagingDirectory = null;
            return;
        }
        try (var paths = Files.walk(stagingDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup of an owned temporary directory.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup of an owned temporary directory.
        } finally {
            stagingDirectory = null;
        }
    }
}
