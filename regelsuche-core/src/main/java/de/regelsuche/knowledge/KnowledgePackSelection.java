package de.regelsuche.knowledge;

import java.util.LinkedHashSet;
import java.util.Set;

public record KnowledgePackSelection(RuleProfile profile, Set<String> enabledPacks, Set<String> disabledPacks) {
    public static final KnowledgePackSelection CORE = new KnowledgePackSelection(RuleProfile.CORE, Set.of(), Set.of());

    public KnowledgePackSelection {
        profile = profile == null ? RuleProfile.CORE : profile;
        enabledPacks = enabledPacks == null ? Set.of() : Set.copyOf(enabledPacks);
        disabledPacks = disabledPacks == null ? Set.of() : Set.copyOf(disabledPacks);
    }

    public static KnowledgePackSelection profile(RuleProfile profile) {
        return new KnowledgePackSelection(profile, Set.of(), Set.of());
    }

    public KnowledgePackSelection enablePack(String packId) {
        Set<String> enabled = new LinkedHashSet<>(enabledPacks);
        Set<String> disabled = new LinkedHashSet<>(disabledPacks);
        enabled.add(packId);
        disabled.remove(packId);
        return new KnowledgePackSelection(profile, enabled, disabled);
    }

    public KnowledgePackSelection disablePack(String packId) {
        Set<String> enabled = new LinkedHashSet<>(enabledPacks);
        Set<String> disabled = new LinkedHashSet<>(disabledPacks);
        disabled.add(packId);
        enabled.remove(packId);
        return new KnowledgePackSelection(profile, enabled, disabled);
    }

    public Set<String> effectiveEnabledPacks(Set<String> availablePacks) {
        Set<String> effective = new LinkedHashSet<>();
        if (profile.enableAllPacks()) {
            effective.addAll(availablePacks);
        } else {
            effective.addAll(profile.enabledPackIds());
        }
        effective.addAll(enabledPacks);
        effective.removeAll(disabledPacks);
        return Set.copyOf(effective);
    }
}
