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
        // Only an absent declaration defaults to any. Preserve supplied text so
        // the runtime's strict constraint parser can reject malformed input.
        versionConstraint = versionConstraint == null ? "any" : versionConstraint;
    }
}
