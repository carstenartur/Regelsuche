package de.regelsuche.plugin;

@de.regelsuche.api.StableApi(since = "1")
public final class ExampleRegistry extends PluginExtensionRegistry<ExamplePackage> {
    public ExampleRegistry() {
        super("example package");
    }
}
