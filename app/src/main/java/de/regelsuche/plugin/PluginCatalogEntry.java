package de.regelsuche.plugin;

import java.util.List;

public record PluginCatalogEntry(
    String id,
    String name,
    String version,
    String source,
    boolean enabled,
    String apiVersion,
    String minimumCoreVersion,
    List<String> capabilities,
    List<PluginCatalogDependency> dependencies,
    String compatibility,
    List<String> compatibilityIssues,
    String provenance,
    boolean signaturePresent,
    boolean signatureVerified,
    boolean trustedSource,
    List<String> trustWarnings
) {
    public PluginCatalogEntry {
        capabilities = List.copyOf(capabilities);
        dependencies = List.copyOf(dependencies);
        compatibilityIssues = List.copyOf(compatibilityIssues);
        trustWarnings = List.copyOf(trustWarnings);
    }

    public static PluginCatalogEntry from(PluginRuntime.LoadedPlugin plugin) {
        return new PluginCatalogEntry(
            plugin.id(),
            plugin.name(),
            plugin.version(),
            plugin.source(),
            plugin.enabled(),
            plugin.apiVersion(),
            plugin.minimumCoreVersion(),
            plugin.capabilities(),
            plugin.dependencies(),
            plugin.compatibility(),
            plugin.compatibilityIssues(),
            plugin.provenance(),
            plugin.signaturePresent(),
            plugin.signatureVerified(),
            plugin.trustedSource(),
            plugin.trustWarnings()
        );
    }
}
