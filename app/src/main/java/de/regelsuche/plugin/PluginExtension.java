package de.regelsuche.plugin;

import java.util.List;

public interface PluginExtension {
    String id();

    default String name() {
        return id();
    }

    default List<String> tags() {
        return List.of();
    }
}
