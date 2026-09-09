package de.regelsuche.plugin;

@de.regelsuche.api.StableApi(since = "1")
public final class ExplanationRegistry extends PluginExtensionRegistry<ExplanationProvider> {
    public ExplanationRegistry() {
        super("explanation provider");
    }
}
