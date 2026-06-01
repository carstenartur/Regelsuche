package de.regelsuche.docs;

import de.regelsuche.benchmark.DiscoveryExpectation;
import de.regelsuche.knowledge.SearchEffect;
import java.util.List;

public record DiscoveryBenchmarkScenario(
        String id,
        String displayName,
        String inputExpression,
        String targetExpression,
        List<DiscoveryExpectation> expectations,
        List<String> enabledOperators,
        List<String> enabledRulePacks,
        List<SearchEffect> requiredBridgeEffects,
        List<String> requiredRuleFamilies,
        List<String> requiredBridgeRules,
        MacroLearning macroLearning,
        Budgets budgets,
        Gallery gallery) {
    public DiscoveryBenchmarkScenario {
        expectations = expectations == null ? List.of() : List.copyOf(expectations);
        enabledOperators = enabledOperators == null ? List.of() : List.copyOf(enabledOperators);
        enabledRulePacks = enabledRulePacks == null ? List.of() : List.copyOf(enabledRulePacks);
        requiredBridgeEffects = requiredBridgeEffects == null ? List.of() : List.copyOf(requiredBridgeEffects);
        requiredRuleFamilies = requiredRuleFamilies == null ? List.of() : List.copyOf(requiredRuleFamilies);
        requiredBridgeRules = requiredBridgeRules == null ? List.of() : List.copyOf(requiredBridgeRules);
        macroLearning = macroLearning == null ? new MacroLearning(false, null, null) : macroLearning;
        gallery = gallery == null ? new Gallery(false, 1, 1) : gallery;
    }

    public record MacroLearning(boolean enabled, String reuseInputExpression, String expectedMacroRule) {
    }

    public record Budgets(int maxDepth, int maxStates, long timeoutMillis) {
    }

    public record Gallery(boolean generateSvg, int preferredPathCount, int minVisibleNodes) {
    }
}
