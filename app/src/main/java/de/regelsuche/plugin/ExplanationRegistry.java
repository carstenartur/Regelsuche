package de.regelsuche.plugin;

public final class ExplanationRegistry extends PluginExtensionRegistry<ExplanationProvider> {
    public ExplanationRegistry() {
        super("explanation provider");
    }
}
