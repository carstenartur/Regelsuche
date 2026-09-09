package de.regelsuche.plugin;

@de.regelsuche.api.StableApi(since = "1")
public interface Heuristic extends PluginExtension {
    int score(String expression);
}
