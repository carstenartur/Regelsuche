package de.regelsuche.plugin;

@de.regelsuche.api.StableApi(since = "1")
public final class RendererRegistry extends PluginExtensionRegistry<Renderer> {
    public RendererRegistry() {
        super("renderer");
    }
}
