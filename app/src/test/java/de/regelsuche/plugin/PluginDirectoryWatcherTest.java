package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginDirectoryWatcherTest {
    @Test
    void watchesRuleFileCreate(@TempDir Path tempDir) throws Exception {
        Path rulesDir = Files.createDirectories(tempDir.resolve("rules"));
        try (PluginRuntime runtime = runtime(tempDir, rulesDir)) {
            List<PluginReloadResult> results = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            try (PluginDirectoryWatcher watcher = watcher(runtime, results, latch)) {
                Files.writeString(rulesDir.resolve("created.regelsuche"), simpleRule("created_rule", "A + 0", "A"));

                assertTrue(latch.await(5, TimeUnit.SECONDS));
            }
            assertTrue(results.getLast().ruleFileChanges().stream()
                .anyMatch(change -> change.id().endsWith("created.regelsuche")
                    && change.type() == PluginReloadChange.ChangeType.ADDED));
        }
    }

    @Test
    void watchesRuleFileModify(@TempDir Path tempDir) throws Exception {
        Path rulesDir = Files.createDirectories(tempDir.resolve("rules"));
        Path file = rulesDir.resolve("changed.regelsuche");
        Files.writeString(file, simpleRule("changed_rule", "A + 0", "A"));

        try (PluginRuntime runtime = runtime(tempDir, rulesDir)) {
            List<PluginReloadResult> results = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            try (PluginDirectoryWatcher watcher = watcher(runtime, results, latch)) {
                Files.writeString(file, simpleRule("changed_rule", "A * 1", "A"));

                assertTrue(latch.await(5, TimeUnit.SECONDS));
            }
            assertTrue(results.getLast().ruleFileChanges().stream()
                .anyMatch(change -> change.id().endsWith("changed.regelsuche")
                    && change.type() == PluginReloadChange.ChangeType.CHANGED));
        }
    }

    @Test
    void watchesRuleFileDelete(@TempDir Path tempDir) throws Exception {
        Path rulesDir = Files.createDirectories(tempDir.resolve("rules"));
        Path file = rulesDir.resolve("removed.regelsuche");
        Files.writeString(file, simpleRule("removed_rule", "A + 0", "A"));

        try (PluginRuntime runtime = runtime(tempDir, rulesDir)) {
            List<PluginReloadResult> results = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            try (PluginDirectoryWatcher watcher = watcher(runtime, results, latch)) {
                Files.delete(file);

                assertTrue(latch.await(5, TimeUnit.SECONDS));
            }
            assertTrue(results.getLast().ruleFileChanges().stream()
                .anyMatch(change -> change.id().endsWith("removed.regelsuche")
                    && change.type() == PluginReloadChange.ChangeType.REMOVED));
        }
    }

    @Test
    void debouncesFastRuleFileChanges(@TempDir Path tempDir) throws Exception {
        Path rulesDir = Files.createDirectories(tempDir.resolve("rules"));
        Path file = rulesDir.resolve("debounced.regelsuche");

        try (PluginRuntime runtime = runtime(tempDir, rulesDir)) {
            CountDownLatch firstReload = new CountDownLatch(1);
            CountDownLatch secondReload = new CountDownLatch(2);
            List<PluginReloadResult> results = new CopyOnWriteArrayList<>();
            try (PluginDirectoryWatcher watcher = new PluginDirectoryWatcher(runtime, Duration.ofMillis(150), result -> {
                results.add(result);
                firstReload.countDown();
                secondReload.countDown();
            })) {
                watcher.start();
                Files.writeString(file, simpleRule("debounced_rule", "A + 0", "A"));
                Files.writeString(file, simpleRule("debounced_rule", "A * 1", "A"));
                Files.writeString(file, simpleRule("debounced_rule", "A - 0", "A"));

                // Wait for the single debounced reload to happen
                assertTrue(firstReload.await(5, TimeUnit.SECONDS));
                // Then assert no second reload occurs in a short window
                assertFalse(secondReload.await(400, TimeUnit.MILLISECONDS));
            }
            assertEquals(1, results.size());
            assertTrue(results.getFirst().ruleFileChanges().stream()
                .anyMatch(change -> change.id().endsWith("debounced.regelsuche")));
        }
    }

    private PluginDirectoryWatcher watcher(
        PluginRuntime runtime,
        List<PluginReloadResult> results,
        CountDownLatch latch
    ) throws Exception {
        PluginDirectoryWatcher watcher = new PluginDirectoryWatcher(runtime, Duration.ofMillis(50), result -> {
            results.add(result);
            latch.countDown();
        });
        watcher.start();
        return watcher;
    }

    private PluginRuntime runtime(Path tempDir, Path rulesDir) {
        return new PluginRuntime(new PluginRuntimeConfig(tempDir.resolve("plugins"), rulesDir, false, Set.of(), Set.of()));
    }

    private String simpleRule(String id, String pattern, String replace) {
        return """
            rule %s:
              pattern: %s
              replace: %s
            """.formatted(id, pattern, replace);
    }
}
