package de.regelsuche.knowledge;

import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.transform.PatternRewriteRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class KnowledgePackRegistry {
    private final Map<String, KnowledgePack> packsById;

    public KnowledgePackRegistry() {
        this(new KnowledgePackLoader().loadClasspathPacks());
    }

    public KnowledgePackRegistry(List<KnowledgePack> packs) {
        Map<String, KnowledgePack> indexed = new LinkedHashMap<>();
        for (KnowledgePack pack : packs) {
            if (indexed.put(pack.packId(), pack) != null) {
                throw new IllegalArgumentException("Duplicate knowledge pack id: " + pack.packId());
            }
        }
        this.packsById = Map.copyOf(indexed);
    }

    public List<KnowledgePack> allPacks() {
        return packsById.values().stream().toList();
    }

    public List<KnowledgePack> enabledPacks(KnowledgePackSelection options) {
        Set<String> defaultEnabled = packsById.values().stream()
                .filter(KnowledgePack::enabledByDefault)
                .map(KnowledgePack::packId)
                .collect(Collectors.toSet());
        Set<String> enabled = options.effectiveEnabledPacks(packsById.keySet(), defaultEnabled);
        Set<String> explicitlyEnabled = explicitlyEnabledPacks(options);
        return packsById.values().stream()
                .filter(pack -> enabled.contains(pack.packId()))
                .filter(pack -> pack.maturity() != KnowledgePackMaturity.EXPERIMENTAL
                        || explicitlyEnabled.contains(pack.packId()))
                .toList();
    }

    public List<PatternRewriteRule> enabledRules(KnowledgePackSelection options) {
        return enabledPacks(options).stream()
                .flatMap(pack -> pack.rules().stream())
                .filter(rule -> rule.descriptor().eligibleForRegistration())
                .toList();
    }

    private Set<String> explicitlyEnabledPacks(KnowledgePackSelection options) {
        Set<String> explicit = new java.util.LinkedHashSet<>(options.enabledPacks());
        explicit.addAll(options.profile().enabledPackIds());
        if (options.profile().enableAllPacks()) {
            explicit.addAll(packsById.keySet());
        }
        return Set.copyOf(explicit);
    }
}
