package de.regelsuche.learning;

import de.regelsuche.inventory.ReusableRule;
import java.util.List;

/**
 * Outcome of a single {@link MacroRuleLearningService#learn} invocation.
 *
 * @param touchedRules   rules updated (created or refreshed) during this step
 * @param newlyActivated rules that crossed the activation threshold in this step
 */
public record MacroLearningResult(
    List<ReusableRule> touchedRules,
    List<ReusableRule> newlyActivated
) {
    public MacroLearningResult {
        touchedRules = List.copyOf(touchedRules);
        newlyActivated = List.copyOf(newlyActivated);
    }
}
