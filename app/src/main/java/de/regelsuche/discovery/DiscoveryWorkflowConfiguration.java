package de.regelsuche.discovery;

import de.regelsuche.mining.GoalAwareMacroMoveSelector;
import java.util.Optional;

/** Explicit app-level wiring for scientific discovery orchestration. */
public record DiscoveryWorkflowConfiguration(
    DiscoveryOptions options,
    HypothesisOperatorRegistry operatorRegistry,
    Optional<GoalAwareMacroMoveSelector> macroMoveSelector,
    Optional<MacroLearningService> macroLearningService
) {
    public DiscoveryWorkflowConfiguration {
        options = options == null ? DiscoveryOptions.forProfile(DiscoveryProfile.RESEARCH_DISCOVERY_PIPELINE) : options;
        operatorRegistry = operatorRegistry == null ? new HypothesisOperatorRegistry() : operatorRegistry;
        macroMoveSelector = macroMoveSelector == null ? Optional.empty() : macroMoveSelector;
        macroLearningService = macroLearningService == null ? Optional.empty() : macroLearningService;
    }

    public static DiscoveryWorkflowConfiguration defaults() {
        return new DiscoveryWorkflowConfiguration(
            DiscoveryOptions.forProfile(DiscoveryProfile.RESEARCH_DISCOVERY_PIPELINE),
            new HypothesisOperatorRegistry(),
            Optional.empty(),
            Optional.empty()
        );
    }

    public boolean macroLearningEnabled() {
        return options.learning().enableMacroLearning() && macroLearningService.isPresent();
    }

    public DiscoveryLearningOptions effectiveLearningOptions() {
        return macroLearningEnabled() ? options.learning() : DiscoveryLearningOptions.disabled();
    }
}
