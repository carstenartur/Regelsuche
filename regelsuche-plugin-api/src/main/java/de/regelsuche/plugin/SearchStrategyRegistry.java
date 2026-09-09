package de.regelsuche.plugin;

@de.regelsuche.api.StableApi(since = "1")
public final class SearchStrategyRegistry extends PluginExtensionRegistry<SearchStrategy> {
    public SearchStrategyRegistry() {
        super("search strategy");
    }
}
