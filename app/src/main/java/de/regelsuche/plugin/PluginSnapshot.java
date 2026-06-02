package de.regelsuche.plugin;

public record PluginSnapshot(
    String id,
    String name,
    String version,
    String source,
    boolean enabled
) {
    static PluginSnapshot from(PluginRuntime.LoadedPlugin plugin) {
        return new PluginSnapshot(plugin.id(), plugin.name(), plugin.version(), plugin.source(), plugin.enabled());
    }
}
