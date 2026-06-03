package de.regelsuche.plugin;

import java.util.List;

public record PluginReloadResult(
    List<PluginReloadChange> pluginChanges,
    List<PluginReloadChange> ruleFileChanges,
    List<PluginRuntime.RuntimeDiagnostic> diagnostics,
    List<RuleConflictDetector.RuleConflict> conflicts,
    List<RuleConflictDetector.CyclicConflict> cyclicConflicts
) {
    public PluginReloadResult {
        pluginChanges = List.copyOf(pluginChanges);
        ruleFileChanges = List.copyOf(ruleFileChanges);
        diagnostics = List.copyOf(diagnostics);
        conflicts = List.copyOf(conflicts);
        cyclicConflicts = List.copyOf(cyclicConflicts);
    }

    public static PluginReloadResult empty() {
        return new PluginReloadResult(List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
