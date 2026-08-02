package de.regelsuche.plugin;

import de.regelsuche.knowledge.KnowledgePackSelection;

import java.nio.file.Path;
import java.util.Set;

public record PluginRuntimeConfig(
    Path pluginsDirectory,
    Path rulesDirectory,
    boolean loadClasspathPlugins,
    Set<String> disabledPluginIds,
    Set<String> disabledRuleIds,
    String activeProfile,
    KnowledgePackSelection knowledgePackSelection
) {
    public PluginRuntimeConfig {
        pluginsDirectory = pluginsDirectory == null ? Path.of("plugins") : pluginsDirectory;
        rulesDirectory = rulesDirectory == null ? Path.of("rules") : rulesDirectory;
        disabledPluginIds = Set.copyOf(disabledPluginIds == null ? Set.of() : disabledPluginIds);
        disabledRuleIds = Set.copyOf(disabledRuleIds == null ? Set.of() : disabledRuleIds);
        activeProfile = activeProfile == null || activeProfile.isBlank() ? null : activeProfile;
        knowledgePackSelection = knowledgePackSelection == null
            ? KnowledgePackSelection.CORE
            : knowledgePackSelection;
    }

    public PluginRuntimeConfig(
        Path pluginsDirectory,
        Path rulesDirectory,
        boolean loadClasspathPlugins,
        Set<String> disabledPluginIds,
        Set<String> disabledRuleIds,
        String activeProfile
    ) {
        this(pluginsDirectory, rulesDirectory, loadClasspathPlugins, disabledPluginIds, disabledRuleIds,
            activeProfile, KnowledgePackSelection.CORE);
    }

    public PluginRuntimeConfig withKnowledgePackSelection(KnowledgePackSelection selection) {
        return new PluginRuntimeConfig(pluginsDirectory, rulesDirectory, loadClasspathPlugins,
            disabledPluginIds, disabledRuleIds, activeProfile, selection);
    }

    public PluginRuntimeConfig(
        Path pluginsDirectory,
        Path rulesDirectory,
        boolean loadClasspathPlugins,
        Set<String> disabledPluginIds,
        Set<String> disabledRuleIds
    ) {
        this(pluginsDirectory, rulesDirectory, loadClasspathPlugins, disabledPluginIds, disabledRuleIds, null,
            KnowledgePackSelection.CORE);
    }

    public static PluginRuntimeConfig defaults() {
        return new PluginRuntimeConfig(Path.of("plugins"), Path.of("rules"), true, Set.of(), Set.of(), null,
            KnowledgePackSelection.CORE);
    }
}
