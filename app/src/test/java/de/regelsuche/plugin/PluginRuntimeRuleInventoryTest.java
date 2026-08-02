package de.regelsuche.plugin;

import de.regelsuche.knowledge.CoreRuleCatalog;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.RuleInventoryManifest;
import de.regelsuche.knowledge.RuleProfile;
import de.regelsuche.knowledge.RuleTier;
import de.regelsuche.transform.RewriteRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginRuntimeRuleInventoryTest {

    @Test
    void ablationProfileRemovesFirstPartyRulesButKeepsPluginContributions(@TempDir Path tempDir) {
        try (PluginRuntime full = runtime(tempDir, KnowledgePackSelection.CORE);
             PluginRuntime kernel = runtime(tempDir,
                 KnowledgePackSelection.profile(RuleProfile.MINIMAL_KERNEL))) {
            List<String> fullRules = ruleIds(full);
            List<String> kernelRules = ruleIds(kernel);
            assertTrue(fullRules.contains("ast_square_difference_factor"));
            assertFalse(kernelRules.contains("ast_square_difference_factor"));
            assertTrue(kernelRules.contains("ast_canonical_normalize"));
            Set<String> pluginRuleIds = Set.copyOf(
                full.registeredRules().stream().map(PluginRuntime.RegisteredRuleView::id).toList());
            assertTrue(kernelRules.containsAll(
                pluginRuleIds.stream().filter(kernelRules::contains).toList()));
            assertEquals(pluginRuleIds.stream().filter(fullRules::contains).count(),
                pluginRuleIds.stream().filter(kernelRules::contains).count());
        }
    }

    @Test
    void manifestRecordsTiersAndChangesWithAblation(@TempDir Path tempDir) {
        try (PluginRuntime full = runtime(tempDir, KnowledgePackSelection.CORE);
             PluginRuntime ablated = runtime(tempDir,
                 KnowledgePackSelection.CORE.disablePack(CoreRuleCatalog.FACTORIZATION))) {
            RuleInventoryManifest fullManifest = full.ruleInventoryManifest();
            RuleInventoryManifest ablatedManifest = ablated.ruleInventoryManifest();
            assertFalse(fullManifest.contentHash().isBlank());
            assertNotEquals(fullManifest.contentHash(), ablatedManifest.contentHash());
            assertFalse(fullManifest.packsByTier(RuleTier.KERNEL).isEmpty());
            assertFalse(fullManifest.packsByTier(RuleTier.PLUGIN).isEmpty());
            assertTrue(ablatedManifest.packs().stream()
                .anyMatch(entry -> CoreRuleCatalog.FACTORIZATION.equals(entry.packId()) && !entry.enabled()));
            assertTrue(fullManifest.ruleIds().containsAll(ablatedManifest.ruleIds()));
        }
    }

    @Test
    void manifestIsReproducibleForTheSameSelection(@TempDir Path tempDir) {
        try (PluginRuntime first = runtime(tempDir, KnowledgePackSelection.CORE);
             PluginRuntime second = runtime(tempDir, KnowledgePackSelection.profile(RuleProfile.FULL))) {
            assertEquals(
                first.ruleInventoryManifest().ruleIds(),
                second.ruleInventoryManifest().ruleIds());
        }
    }

    private static PluginRuntime runtime(Path tempDir, KnowledgePackSelection selection) {
        return new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            tempDir.resolve("rules"),
            true,
            Set.of(),
            Set.of(),
            null,
            selection));
    }

    private static List<String> ruleIds(PluginRuntime runtime) {
        return runtime.createTransformationEngine().rules().stream().map(RewriteRule::id).toList();
    }
}
