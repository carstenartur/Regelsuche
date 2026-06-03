package de.regelsuche.plugin;

import java.util.List;

public record RuleFileSnapshot(
    String path,
    int loadedEntries,
    boolean loaded,
    List<String> diagnostics,
    String contentHash
) {
    public RuleFileSnapshot {
        diagnostics = List.copyOf(diagnostics);
    }

    static RuleFileSnapshot from(PluginRuntime.LoadedRuleFile ruleFile) {
        return new RuleFileSnapshot(
            ruleFile.path(),
            ruleFile.loadedEntries(),
            ruleFile.loaded(),
            ruleFile.diagnostics(),
            ruleFile.contentHash()
        );
    }
}
