package de.regelsuche.plugin;

public record PluginCatalogDependency(String pluginId, String versionConstraint, boolean optional, String status) {
    public PluginCatalogDependency {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId must not be blank");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        pluginId = pluginId.trim().toLowerCase(java.util.Locale.ROOT);
        versionConstraint = versionConstraint == null || versionConstraint.isBlank()
            ? "any"
            : versionConstraint.trim();
        status = status.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
