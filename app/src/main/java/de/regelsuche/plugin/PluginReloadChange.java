package de.regelsuche.plugin;

public record PluginReloadChange(String id, ChangeType type) {
    public enum ChangeType { ADDED, REMOVED, CHANGED }
}
