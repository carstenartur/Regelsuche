package de.regelsuche.search.convergence;

import de.regelsuche.scoring.ExpressionScore;
import java.util.List;

public record ConvergentPath(
    String pathId,
    List<String> expressions,
    List<String> ruleIds,
    List<RuleFamily> ruleFamilies,
    String finalExpression,
    ExpressionScore score,
    int length,
    boolean containsHypothesisStep,
    boolean containsMacroStep,
    boolean containsLearnedRule,
    String proofStatus,
    String validationStatus,
    List<String> sourceReplayIds
) {
    public ConvergentPath {
        expressions = List.copyOf(expressions);
        ruleIds = List.copyOf(ruleIds);
        ruleFamilies = List.copyOf(ruleFamilies);
        sourceReplayIds = sourceReplayIds == null ? List.of() : List.copyOf(sourceReplayIds);
    }
}
