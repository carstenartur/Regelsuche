package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginReloadResultTest {
    @Test
    void emptyResultHasNoChangesOrDiagnostics() {
        PluginReloadResult result = PluginReloadResult.empty();

        assertTrue(result.pluginChanges().isEmpty());
        assertTrue(result.ruleFileChanges().isEmpty());
        assertTrue(result.diagnostics().isEmpty());
        assertTrue(result.conflicts().isEmpty());
        assertTrue(result.cyclicConflicts().isEmpty());
    }

    @Test
    void resultDefensivelyCopiesLists() {
        List<PluginReloadChange> changes = new ArrayList<>();
        changes.add(new PluginReloadChange("rules.regelsuche", PluginReloadChange.ChangeType.ADDED));

        PluginReloadResult result = new PluginReloadResult(changes, List.of(), List.of(), List.of(), List.of());
        changes.clear();

        assertEquals(1, result.pluginChanges().size());
        assertThrows(UnsupportedOperationException.class,
            () -> result.pluginChanges().add(new PluginReloadChange("other", PluginReloadChange.ChangeType.REMOVED)));
    }
}
