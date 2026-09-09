package de.regelsuche.plugin;

@de.regelsuche.api.StableApi(since = "1")
public interface Renderer extends PluginExtension {
    boolean supports(String format);

    String render(String expression);
}
