package de.regelsuche.plugin;

@de.regelsuche.api.StableApi(since = "1")
public final class ParserExtensionRegistry extends PluginExtensionRegistry<ParserExtension> {
    public ParserExtensionRegistry() {
        super("parser extension");
    }
}
