package de.regelsuche.plugin;

public interface ExplanationProvider extends PluginExtension {
    boolean supportsRule(String ruleId);

    String explain(String ruleId, String expression);
}
