package de.regelsuche.search;

import de.regelsuche.knowledge.SearchEffect;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record SearchSpaceAnalytics(
        int statesExplored,
        int uniqueCanonicalStates,
        int convergentStates,
        int learnedMacroUsage,
        double averageBranchingFactor,
        Map<String, Long> ruleUsage,
        Map<String, Long> topBridgeRules,
        Map<String, Long> topFactorizationRules,
        Map<String, Long> topSimplificationRules,
        Map<String, Long> topConvergentNodes) {

    public SearchSpaceAnalytics(
            int statesExplored,
            int uniqueCanonicalStates,
            int convergentStates,
            int learnedMacroUsage,
            double averageBranchingFactor) {
        this(statesExplored, uniqueCanonicalStates, convergentStates, learnedMacroUsage, averageBranchingFactor,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    public SearchSpaceAnalytics {
        ruleUsage = ruleUsage == null ? Map.of() : Map.copyOf(ruleUsage);
        topBridgeRules = topBridgeRules == null ? Map.of() : Map.copyOf(topBridgeRules);
        topFactorizationRules = topFactorizationRules == null ? Map.of() : Map.copyOf(topFactorizationRules);
        topSimplificationRules = topSimplificationRules == null ? Map.of() : Map.copyOf(topSimplificationRules);
        topConvergentNodes = topConvergentNodes == null ? Map.of() : Map.copyOf(topConvergentNodes);
    }

    public static SearchSpaceAnalytics from(Map<String, Long> generatedStateCounts,
                                            Map<String, Long> ruleUsage,
                                            long macroApplications) {
        return from(generatedStateCounts, ruleUsage, macroApplications, Map.of());
    }

    public static SearchSpaceAnalytics from(Map<String, Long> generatedStateCounts,
                                            Map<String, Long> ruleUsage,
                                            long macroApplications,
                                            Map<String, Set<SearchEffect>> ruleEffects) {
        int generated = (int) generatedStateCounts.values().stream().mapToLong(Long::longValue).sum();
        int repeated = (int) generatedStateCounts.values().stream().filter(count -> count > 1).count();
        return new SearchSpaceAnalytics(
                generated,
                generatedStateCounts.size(),
                repeated,
                (int) macroApplications,
                0.0d,
                ruleUsage,
                topRulesByEffect(ruleUsage, ruleEffects, SearchEffect.BRIDGING),
                topRulesByEffect(ruleUsage, ruleEffects, SearchEffect.FACTORIZING),
                topRulesByEffect(ruleUsage, ruleEffects, SearchEffect.SIMPLIFYING),
                generatedStateCounts.entrySet().stream()
                        .filter(entry -> entry.getValue() > 1)
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                                (left, right) -> left, java.util.LinkedHashMap::new)));
    }

    public Map<String, Long> topRules(int limit) {
        return ruleUsage.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, java.util.LinkedHashMap::new));
    }

    public long generatedStates() {
        return statesExplored;
    }

    public long repeatedStates() {
        return convergentStates;
    }

    public long macroApplications() {
        return learnedMacroUsage;
    }

    private static Map<String, Long> topRulesByEffect(
            Map<String, Long> ruleUsage,
            Map<String, Set<SearchEffect>> ruleEffects,
            SearchEffect effect) {
        return ruleUsage.entrySet().stream()
                .filter(entry -> ruleEffects.getOrDefault(entry.getKey(), Set.of()).contains(effect))
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, java.util.LinkedHashMap::new));
    }
}
