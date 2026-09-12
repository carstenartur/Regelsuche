package de.regelsuche.search.reachability;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.Transformation;
import java.util.List;

/** Independent concrete replay; occurrence/guard certificates are checked by V3. */
final class OccurrencePreparationReplay {
    private OccurrencePreparationReplay() { }

    static OccurrenceAwareSharedRulePreparationCoordinator.Verification verify(
        OccurrenceAwareSharedRulePreparationCoordinator.Evaluation evaluation,
        List<RewriteApplicabilitySchema> schemas
    ) {
        for (RewriteApplicabilitySchema schema : schemas) {
            var outcome = evaluation.outcome(schema.ruleId()).orElseThrow();
            if (!outcome.direct()) {
                continue;
            }
            try {
                // V3 selects the first unfiltered direct occurrence. Product
                // search limits are a separate policy, not replay authority.
                var replayed = new AstRewriteTransformationEngine(
                    List.of(schema.executor()), Integer.MAX_VALUE, 1)
                    .transform(evaluation.sourceExpression()).stream()
                    .findFirst()
                    .map(step -> withSourceAssumptions(step, evaluation.sourceAssumptions()));
                if (!replayed.equals(outcome.candidate())) {
                    return new OccurrenceAwareSharedRulePreparationCoordinator.Verification(
                        false, "DIRECT_PRIMITIVE_REPLAY_MISMATCH");
                }
            } catch (RuntimeException exception) {
                return new OccurrenceAwareSharedRulePreparationCoordinator.Verification(
                    false, "DIRECT_PRIMITIVE_REPLAY_TECHNICAL_FAILURE");
            }
        }
        return new OccurrenceAwareSharedRulePreparationCoordinator.Verification(true, "VERIFIED");
    }

    private static Transformation withSourceAssumptions(
        Transformation step, AssumptionSignature sourceAssumptions
    ) {
        AssumptionSignature cumulative = AssumptionSignature.merge(
            sourceAssumptions, AssumptionSignature.ofExpressions(step.assumptions()));
        return new Transformation(
            step.rule(), step.transformedExpression(), step.kind(),
            step.mayIncreaseComplexity(), step.estimatedCostDelta(),
            step.equivalencePreservingByConstruction(), step.applicationKey(),
            cumulative.normalizedAssumptions(), step.packId(), step.license(),
            step.primitiveRuleIds());
    }
}
