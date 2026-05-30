package de.regelsuche.learning;

import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.validation.CounterexampleSearchService;
import java.util.List;

/**
 * Outcome of a single {@link MacroRuleLearningService#learn} invocation.
 *
 * @param touchedRules   rules updated (created or refreshed) during this step
 * @param newlyActivated rules that crossed the activation threshold in this step
 */
public record MacroLearningResult(
    List<ReusableRule> touchedRules,
    List<ReusableRule> newlyActivated,
    List<MacroValidationExample> validationExamples,
    List<CounterexampleSearchService.CounterexampleSearchResult> counterexampleSearches,
    List<String> stageEvidence
) {
    public MacroLearningResult {
        touchedRules = List.copyOf(touchedRules);
        newlyActivated = List.copyOf(newlyActivated);
        validationExamples = validationExamples == null ? List.of() : List.copyOf(validationExamples);
        counterexampleSearches = counterexampleSearches == null ? List.of() : List.copyOf(counterexampleSearches);
        stageEvidence = stageEvidence == null ? List.of() : List.copyOf(stageEvidence);
    }

    public MacroLearningResult(
        List<ReusableRule> touchedRules,
        List<ReusableRule> newlyActivated
    ) {
        this(touchedRules, newlyActivated, List.of(), List.of(), List.of());
    }
}
