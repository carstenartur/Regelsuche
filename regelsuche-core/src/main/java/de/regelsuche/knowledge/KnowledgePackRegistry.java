package de.regelsuche.knowledge;

import de.regelsuche.transform.PatternRewriteRule;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
                throw new IllegalArgumentException(
                    "Duplicate knowledge pack id: " + pack.packId());
            }
        }
        this.packsById = Map.copyOf(indexed);
    }

    public List<KnowledgePack> allPacks() {
        return packsById.values().stream().toList();
    }

    public List<KnowledgePack> packsByTier(RuleTier tier) {
        return packsById.values().stream()
                .filter(pack -> pack.tier() == tier)
                .toList();
    }

    public List<KnowledgePack> enabledPacks(KnowledgePackSelection options) {
        rejectKernelDisable(options);
        Set<String> defaultEnabled = packsById.values().stream()
                .filter(KnowledgePack::enabledByDefault)
                .filter(pack -> pack.tier() == RuleTier.KERNEL
                        || options.profile().includeFirstPartyDefaults())
                .map(KnowledgePack::packId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        packsById.values().stream()
                .filter(pack -> pack.tier() == RuleTier.KERNEL)
                .map(KnowledgePack::packId)
                .forEach(defaultEnabled::add);
        Set<String> enabled = options.effectiveEnabledPacks(
            packsById.keySet(), defaultEnabled);
        Set<String> explicitlyEnabled = explicitlyEnabledPacks(options);
        return packsById.values().stream()
                .filter(pack -> enabled.contains(pack.packId()))
                .filter(pack -> pack.maturity()
                    != KnowledgePackMaturity.EXPERIMENTAL
                    || explicitlyEnabled.contains(pack.packId()))
                .toList();
    }

    public List<PatternRewriteRule> enabledRules(
            KnowledgePackSelection options) {
        return enabledPacks(options).stream()
                .flatMap(pack -> pack.rules().stream())
                .filter(rule -> rule.descriptor().eligibleForRegistration())
                .toList();
    }

    public List<KnownStructureDefinition> enabledKnownStructures(
            KnowledgePackSelection options) {
        return enabledPacks(options).stream()
                .flatMap(pack -> pack.knownStructures().stream())
                .toList();
    }

    private void rejectKernelDisable(KnowledgePackSelection options) {
        for (String packId : options.disabledPacks()) {
            KnowledgePack pack = packsById.get(packId);
            if (pack != null && pack.tier() == RuleTier.KERNEL) {
                throw new IllegalArgumentException(
                    "Kernel knowledge pack cannot be disabled: " + packId);
            }
        }
    }

    private Set<String> explicitlyEnabledPacks(
            KnowledgePackSelection options) {
        Set<String> explicit = new LinkedHashSet<>(options.enabledPacks());
        explicit.addAll(options.profile().enabledPackIds());
        if (options.profile().enableAllPacks()) {
            explicit.addAll(packsById.keySet());
        }
        return Set.copyOf(explicit);
    }
}
