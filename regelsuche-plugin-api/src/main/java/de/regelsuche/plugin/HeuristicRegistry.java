package de.regelsuche.plugin;

@de.regelsuche.api.StableApi(since = "1")
public final class HeuristicRegistry extends PluginExtensionRegistry<Heuristic> {
    public HeuristicRegistry() {
        super("heuristic");
    }
}
