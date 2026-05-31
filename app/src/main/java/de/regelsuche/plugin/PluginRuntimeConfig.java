package de.regelsuche.plugin;

import java.nio.file.Path;
import java.util.Set;

public record PluginRuntimeConfig(
    Path pluginsDirectory,
    Path rulesDirectory,
    boolean loadClasspathPlugins,
    Set<String> disabledPluginIds,
    Set<String> disabledRuleIds
) {
    public PluginRuntimeConfig {
        pluginsDirectory = pluginsDirectory == null ? Path.of("plugins") : pluginsDirectory;
        rulesDirectory = rulesDirectory == null ? Path.of("rules") : rulesDirectory;
        disabledPluginIds = Set.copyOf(disabledPluginIds == null ? Set.of() : disabledPluginIds);
        disabledRuleIds = Set.copyOf(disabledRuleIds == null ? Set.of() : disabledRuleIds);
    }

    public static PluginRuntimeConfig defaults() {
        return new PluginRuntimeConfig(Path.of("plugins"), Path.of("rules"), true, Set.of(), Set.of());
    }
}
