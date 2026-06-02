package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginRuntimeReloadDiffTest {
    @Test
    void unchangedRuleFileDoesNotProduceReloadChange(@TempDir Path tempDir) throws Exception {
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("local.regelsuche"), simpleRule("local_rule", "A + 0", "A"));

        try (PluginRuntime runtime = runtime(tempDir, rulesDir)) {
            PluginReloadResult result = runtime.reloadWithResult();

            assertTrue(result.ruleFileChanges().isEmpty());
            assertTrue(result.pluginChanges().isEmpty());
        }
    }

    @Test
    void changedRuleFileContentProducesChangedReloadEntry(@TempDir Path tempDir) throws Exception {
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Path file = rulesDir.resolve("local.regelsuche");
        Files.writeString(file, simpleRule("local_rule", "A + 0", "A"));

        try (PluginRuntime runtime = runtime(tempDir, rulesDir)) {
            Files.writeString(file, simpleRule("local_rule", "A * 1", "A"));

            PluginReloadResult result = runtime.reloadWithResult();

            assertEquals(
                new PluginReloadChange(file.toString(), PluginReloadChange.ChangeType.CHANGED),
                result.ruleFileChanges().getFirst()
            );
        }
    }

    @Test
    void addedAndRemovedRuleFilesAreReported(@TempDir Path tempDir) throws Exception {
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Path removed = rulesDir.resolve("removed.regelsuche");
        Path added = rulesDir.resolve("added.regelsuche");
        Files.writeString(removed, simpleRule("removed_rule", "A + 0", "A"));

        try (PluginRuntime runtime = runtime(tempDir, rulesDir)) {
            Files.delete(removed);
            Files.writeString(added, simpleRule("added_rule", "A * 1", "A"));

            PluginReloadResult result = runtime.reloadWithResult();

            assertTrue(result.ruleFileChanges().contains(
                new PluginReloadChange(removed.toString(), PluginReloadChange.ChangeType.REMOVED)));
            assertTrue(result.ruleFileChanges().contains(
                new PluginReloadChange(added.toString(), PluginReloadChange.ChangeType.ADDED)));
        }
    }

    private PluginRuntime runtime(Path tempDir, Path rulesDir) {
        return new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            rulesDir,
            false,
            Set.of(),
            Set.of()
        ));
    }

    private String simpleRule(String id, String pattern, String replace) {
        return """
            rule %s:
              pattern: %s
              replace: %s
            """.formatted(id, pattern, replace);
    }
}
