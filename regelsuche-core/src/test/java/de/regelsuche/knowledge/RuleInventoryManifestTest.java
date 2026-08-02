package de.regelsuche.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleInventoryManifestTest {

    @Test
    void manifestHashIsStableAcrossPackAndRuleOrdering() {
        RuleInventoryManifest first = RuleInventoryManifest.of(
                "full",
                List.of(
                        new RuleInventoryManifest.PackEntry("a", RuleTier.KERNEL, "core", true, 2),
                        new RuleInventoryManifest.PackEntry("b", RuleTier.FIRST_PARTY, "core", true, 1)),
                List.of("r1", "r2", "r3"));
        RuleInventoryManifest second = RuleInventoryManifest.of(
                "full",
                List.of(
                        new RuleInventoryManifest.PackEntry("b", RuleTier.FIRST_PARTY, "core", true, 1),
                        new RuleInventoryManifest.PackEntry("a", RuleTier.KERNEL, "core", true, 2)),
                List.of("r3", "r1", "r2"));
        assertEquals(first.contentHash(), second.contentHash());
        assertFalse(first.contentHash().isBlank());
    }

    @Test
    void disablingAPackChangesTheHash() {
        RuleInventoryManifest full = new RuleInventoryBuilder(KnowledgePackSelection.CORE)
                .withCorePacks()
                .build();
        RuleInventoryManifest ablated = new RuleInventoryBuilder(
                KnowledgePackSelection.CORE.disablePack(CoreRuleCatalog.FACTORIZATION))
                .withCorePacks()
                .build();
        assertNotEquals(full.contentHash(), ablated.contentHash());
        assertTrue(full.ruleIds().size() > ablated.ruleIds().size());
    }

    @Test
    void builderRecordsAllTiers() {
        RuleInventoryManifest manifest = new RuleInventoryBuilder(
                KnowledgePackSelection.profile(RuleProfile.MINIMAL_KERNEL))
                .withCorePacks()
                .withKnowledgePacks(new KnowledgePackRegistry())
                .addPluginPack("example-plugin", List.of("plugin_rule_a"), true)
                .build();
        assertEquals("minimal-kernel", manifest.profileId());
        assertFalse(manifest.packsByTier(RuleTier.KERNEL).isEmpty());
        assertFalse(manifest.packsByTier(RuleTier.FIRST_PARTY).isEmpty());
        assertEquals(1, manifest.packsByTier(RuleTier.PLUGIN).size());
        assertTrue(manifest.ruleIds().contains("plugin_rule_a"));
        assertTrue(manifest.packsByTier(RuleTier.FIRST_PARTY).stream()
                .filter(entry -> "core".equals(entry.source()))
                .noneMatch(RuleInventoryManifest.PackEntry::enabled));
    }
}
