package de.regelsuche.extension;

import de.regelsuche.api.StableApi;
import java.util.Locale;

/** Dependency declared by one plugin on another plugin identity/version. */
@StableApi(since = "2")
public record PluginDependency(
    String pluginId,
    String versionConstraint,
    boolean optional
) {
    public PluginDependency {
        pluginId = ExtensionIdentifiers.identifier(pluginId, "pluginId")
            .toLowerCase(Locale.ROOT);
        versionConstraint = versionConstraint == null || versionConstraint.isBlank()
            ? "any"
            : versionConstraint.trim();
    }
}
