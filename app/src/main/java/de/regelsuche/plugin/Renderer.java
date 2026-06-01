package de.regelsuche.plugin;

public interface Renderer extends PluginExtension {
    boolean supports(String format);

    String render(String expression);
}
