package de.regelsuche.plugin;

public record PluginSnapshot(
    String id,
    String name,
    String version,
    String source,
    boolean enabled,
    String apiVersion,
    String minimumCoreVersion,
    String compatibility,
    boolean trustedSource
) {
    static PluginSnapshot from(PluginRuntime.LoadedPlugin plugin) {
        return new PluginSnapshot(
            plugin.id(),
            plugin.name(),
            plugin.version(),
            plugin.source(),
            plugin.enabled(),
            plugin.apiVersion(),
            plugin.minimumCoreVersion(),
            plugin.compatibility(),
            plugin.trustedSource()
        );
    }
}
