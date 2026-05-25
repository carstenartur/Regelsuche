package de.regelsuche.validation;

import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.List;

public class RewriteRuleValidationService {
    private final TransformationEngine transformationEngine;
    private final EquivalenceService equivalenceService;

    public RewriteRuleValidationService(TransformationEngine transformationEngine, EquivalenceService equivalenceService) {
        this.transformationEngine = transformationEngine;
        this.equivalenceService = equivalenceService;
    }

    public List<RewriteRuleValidationResult> validate(List<String> expressions) {
        List<RewriteRuleValidationResult> results = new ArrayList<>();
        for (String expression : expressions) {
            for (Transformation transformation : transformationEngine.transform(expression)) {
                boolean equivalent = equivalenceService.areEquivalent(expression, transformation.transformedExpression());
                results.add(new RewriteRuleValidationResult(
                    expression,
                    transformation.transformedExpression(),
                    transformation.rule(),
                    equivalent ? RewriteValidationStatus.VALIDATED : RewriteValidationStatus.REJECTED,
                    equivalenceService.evidence(expression, transformation.transformedExpression())
                ));
            }
        }
        return results;
    }

    public boolean allValidated(List<String> expressions) {
        List<RewriteRuleValidationResult> results = validate(expressions);
        return !results.isEmpty() && results.stream().allMatch(result -> result.status() == RewriteValidationStatus.VALIDATED);
    }
}
