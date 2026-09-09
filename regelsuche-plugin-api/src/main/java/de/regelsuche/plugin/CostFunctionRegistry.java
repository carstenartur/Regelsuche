package de.regelsuche.plugin;

@de.regelsuche.api.StableApi(since = "1")
public final class CostFunctionRegistry extends PluginExtensionRegistry<CostFunction> {
    public CostFunctionRegistry() {
        super("cost function");
    }
}
