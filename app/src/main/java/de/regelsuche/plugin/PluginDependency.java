package de.regelsuche.plugin;

public record PluginDependency(String pluginId, String versionConstraint, boolean optional) {
    public PluginDependency {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId must not be blank");
        }
        versionConstraint = versionConstraint == null || versionConstraint.isBlank()
            ? "any"
            : versionConstraint.trim();
    }
}
