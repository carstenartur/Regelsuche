package de.regelsuche.plugin;

import java.util.List;

@de.regelsuche.api.StableApi(since = "1")
public interface PluginExtension {
    String id();

    default String name() {
        return id();
    }

    default List<String> tags() {
        return List.of();
    }
}
