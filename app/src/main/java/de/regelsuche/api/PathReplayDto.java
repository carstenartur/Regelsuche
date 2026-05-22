package de.regelsuche.api;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.explain.ExplanationService;
import java.util.List;

/**
 * Step-wise replay representation of a {@link DiscoveredTransformation}.
 *
 * <p>Returned by {@code GET /api/paths/{id}/replay} (Step 2). Each step
 * carries both raw and LaTeX-rendered expressions plus a human-readable
 * rule explanation, suitable for the replay UI tab.</p>
 */
public record PathReplayDto(
    String pathId,
    List<ReplayStep> steps
) {
    public PathReplayDto {
        if (pathId == null || pathId.isBlank()) {
            throw new IllegalArgumentException("pathId is required");
        }
        steps = List.copyOf(steps);
    }

    public record ReplayStep(
        int stepIndex,
        String fromExpression,
        String fromLatex,
        String toExpression,
        String toLatex,
        String ruleId,
        String ruleExplanation,
        int scoreDelta,
        boolean equivalencePreserving
    ) {
    }

    public static PathReplayDto from(DiscoveredTransformation path, ExplanationService explanationService) {
        List<TransformationStep> steps = path.steps();
        List<ReplayStep> replaySteps = new java.util.ArrayList<>(steps.size());
        for (TransformationStep step : steps) {
            replaySteps.add(new ReplayStep(
                step.index(),
                step.beforeExpression(),
                toLatex(step.beforeExpression()),
                step.afterExpression(),
                toLatex(step.afterExpression()),
                step.ruleId(),
                explanationService.renderStep(step, ExplanationService.Form.SCHOOL),
                step.scoreAfter() - step.scoreBefore(),
                step.equivalencePreserving()
            ));
        }
        return new PathReplayDto(path.id(), replaySteps);
    }

    private static final de.regelsuche.export.MathPresentation MATH =
        de.regelsuche.export.MathPresentation.DEFAULT;

    private static String toLatex(String expression) {
        return MATH.latex(expression);
    }
}
