package de.regelsuche.plugin;

public interface ParserExtension extends PluginExtension {
    boolean supports(String input);

    String normalize(String input);
}
