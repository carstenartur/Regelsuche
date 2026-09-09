package de.regelsuche.plugin;

@de.regelsuche.api.StableApi(since = "1")
public interface ExplanationProvider extends PluginExtension {
    boolean supportsRule(String ruleId);

    String explain(String ruleId, String expression);
}
