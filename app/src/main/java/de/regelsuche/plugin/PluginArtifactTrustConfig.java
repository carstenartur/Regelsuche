package de.regelsuche.plugin;

import java.nio.file.Path;

/** Configuration for the opt-in pre-classloading plugin trust gate. */
public record PluginArtifactTrustConfig(
    Path trustStorePath,
    PluginTrustPolicy policy,
    long maxArtifactBytes
) {
    public static final long DEFAULT_MAX_ARTIFACT_BYTES = 64L * 1024L * 1024L;

    public PluginArtifactTrustConfig(Path trustStorePath, PluginTrustPolicy policy) {
        this(trustStorePath, policy, DEFAULT_MAX_ARTIFACT_BYTES);
    }

    public PluginArtifactTrustConfig {
        trustStorePath = trustStorePath == null
            ? Path.of("plugins", "trust-store.json").toAbsolutePath().normalize()
            : trustStorePath.toAbsolutePath().normalize();
        policy = policy == null ? PluginTrustPolicy.REQUIRE_VERIFIED : policy;
        if (maxArtifactBytes < 1 || maxArtifactBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "maxArtifactBytes must be in [1," + Integer.MAX_VALUE + "]");
        }
    }

    public static PluginArtifactTrustConfig defaults(Path pluginsDirectory) {
        Path directory = pluginsDirectory == null ? Path.of("plugins") : pluginsDirectory;
        return new PluginArtifactTrustConfig(
            directory.resolve("trust-store.json"),
            PluginTrustPolicy.REQUIRE_VERIFIED,
            DEFAULT_MAX_ARTIFACT_BYTES
        );
    }
}
