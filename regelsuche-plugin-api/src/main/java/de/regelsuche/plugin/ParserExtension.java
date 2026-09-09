package de.regelsuche.plugin;

@de.regelsuche.api.StableApi(since = "1")
public interface ParserExtension extends PluginExtension {
    boolean supports(String input);

    String normalize(String input);
}
