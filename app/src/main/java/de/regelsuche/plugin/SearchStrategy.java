package de.regelsuche.plugin;

public interface SearchStrategy extends PluginExtension {
    default String description() {
        return "";
    }
}
