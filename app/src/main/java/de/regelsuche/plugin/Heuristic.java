package de.regelsuche.plugin;

public interface Heuristic extends PluginExtension {
    int score(String expression);
}
