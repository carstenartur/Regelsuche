package de.regelsuche.knowledge;

import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteRule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds a {@link RuleInventoryManifest} from all three rule tiers.
 *
 * <p>Core packs, knowledge packs and plugin contributions are recorded through one path, so a
 * disabled first-party pack and a disabled plugin appear the same way in the manifest.
 */
public final class RuleInventoryBuilder {
    private final KnowledgePackSelection selection;
    private final List<RuleInventoryManifest.PackEntry> packs = new ArrayList<>();
    private final Set<String> ruleIds = new LinkedHashSet<>();

    public RuleInventoryBuilder(KnowledgePackSelection selection) {
        this.selection = selection == null ? KnowledgePackSelection.CORE : selection;
    }

    /** Records every built-in core pack, enabled or not, plus the rule ids of the enabled ones. */
    public RuleInventoryBuilder withCorePacks() {
        Set<String> enabled = CoreRuleCatalog.enabledPackIds(selection);
        for (CoreRulePack pack : CoreRuleCatalog.packs()) {
            boolean packEnabled = enabled.contains(pack.packId());
            packs.add(new RuleInventoryManifest.PackEntry(
                    pack.packId(),
                    pack.tier(),
                    "core",
                    packEnabled,
                    pack.ruleIds().size()));
        }
        for (RewriteRule rule : AstRewriteTransformationEngine.defaultRules(selection)) {
            ruleIds.add(rule.id());
        }
        return this;
    }

    /** Records every knowledge pack on the classpath, enabled or not. */
    public RuleInventoryBuilder withKnowledgePacks(KnowledgePackRegistry registry) {
        if (registry == null) {
            return this;
        }
        Set<String> enabled = new LinkedHashSet<>();
        for (KnowledgePack pack : registry.enabledPacks(selection)) {
            enabled.add(pack.packId());
        }
        for (KnowledgePack pack : registry.allPacks()) {
            packs.add(new RuleInventoryManifest.PackEntry(
                    pack.packId(),
                    pack.tier(),
                    "knowledge-pack",
                    enabled.contains(pack.packId()),
                    pack.rules().size()));
        }
        for (PatternRewriteRule rule : registry.enabledRules(selection)) {
            ruleIds.add(rule.id());
        }
        return this;
    }

    /** Records a plugin contribution as a synthetic pack of the {@link RuleTier#PLUGIN} tier. */
    public RuleInventoryBuilder addPluginPack(String pluginId, List<String> contributedRuleIds, boolean enabled) {
        List<String> contributed = contributedRuleIds == null ? List.of() : List.copyOf(contributedRuleIds);
        packs.add(new RuleInventoryManifest.PackEntry(
                pluginId,
                RuleTier.PLUGIN,
                "plugin",
                enabled,
                contributed.size()));
        if (enabled) {
            ruleIds.addAll(contributed);
        }
        return this;
    }

    public RuleInventoryManifest build() {
        return RuleInventoryManifest.of(selection.profile().id(), List.copyOf(packs), List.copyOf(ruleIds));
    }
}
