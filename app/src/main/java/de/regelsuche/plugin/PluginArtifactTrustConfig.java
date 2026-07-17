package de.regelsuche.plugin;

import java.nio.file.Path;

/** Configuration for the opt-in pre-classloading plugin trust gate. */
public record PluginArtifactTrustConfig(
    Path trustStorePath,
    PluginTrustPolicy policy
) {
    public PluginArtifactTrustConfig {
        trustStorePath = trustStorePath == null
            ? Path.of("plugins", "trust-store.json").toAbsolutePath().normalize()
            : trustStorePath.toAbsolutePath().normalize();
        policy = policy == null ? PluginTrustPolicy.REQUIRE_VERIFIED : policy;
    }

    public static PluginArtifactTrustConfig defaults(Path pluginsDirectory) {
        Path directory = pluginsDirectory == null ? Path.of("plugins") : pluginsDirectory;
        return new PluginArtifactTrustConfig(
            directory.resolve("trust-store.json"),
            PluginTrustPolicy.REQUIRE_VERIFIED
        );
    }
}
