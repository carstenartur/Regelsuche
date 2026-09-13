package de.regelsuche.validation;

import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.equivalence.AssumptionAwareEquivalenceService;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.List;

public class RewriteRuleValidationService {
    private final TransformationEngine transformationEngine;
    private final EquivalenceService equivalenceService;
    private final AssumptionAwareEquivalenceService assumptionAwareEquivalenceService;

    public RewriteRuleValidationService(TransformationEngine transformationEngine, EquivalenceService equivalenceService) {
        this(transformationEngine, equivalenceService, null);
    }

    public RewriteRuleValidationService(TransformationEngine transformationEngine,
        EquivalenceService equivalenceService,
        AssumptionAwareEquivalenceService assumptionAwareEquivalenceService) {
        this.transformationEngine = transformationEngine;
        this.equivalenceService = equivalenceService;
        this.assumptionAwareEquivalenceService = assumptionAwareEquivalenceService;
    }

    public List<RewriteRuleValidationResult> validate(List<String> expressions) {
        List<RewriteRuleValidationResult> results = new ArrayList<>();
        for (String expression : expressions) {
            for (Transformation transformation : transformationEngine.transform(expression)) {
                if (!transformation.assumptions().isEmpty()) {
                    results.add(validateGuarded(expression, transformation));
                    continue;
                }
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

    private RewriteRuleValidationResult validateGuarded(String expression, Transformation transformation) {
        RewriteValidationStatus status = RewriteValidationStatus.UNKNOWN;
        String evidence = "Guarded rewrite requires assumption-aware equivalence validation";
        if (assumptionAwareEquivalenceService != null) {
            var evaluation = assumptionAwareEquivalenceService.evaluate(expression,
                transformation.transformedExpression(), transformation.assumptions());
            status = switch (evaluation.status()) {
                case CONFIRMED -> RewriteValidationStatus.VALIDATED;
                case REFUTED -> RewriteValidationStatus.REJECTED;
                case MISSING_ASSUMPTION, UNSUPPORTED -> RewriteValidationStatus.UNKNOWN;
            };
            evidence = evaluation.detail();
        }
        return new RewriteRuleValidationResult(expression, transformation.transformedExpression(),
            transformation.rule(), status, evidence, transformation.assumptions());
    }

    public boolean allValidated(List<String> expressions) {
        List<RewriteRuleValidationResult> results = validate(expressions);
        return !results.isEmpty() && results.stream().allMatch(result -> result.status() == RewriteValidationStatus.VALIDATED);
    }
}
