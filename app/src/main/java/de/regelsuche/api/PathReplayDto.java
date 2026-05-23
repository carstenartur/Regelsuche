package de.regelsuche.api;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.explain.ExplanationService;
import de.regelsuche.export.MathDiff;
import de.regelsuche.export.MathPresentation;
import java.util.ArrayList;
import java.util.List;

/**
 * Step-wise replay representation of a {@link DiscoveredTransformation}.
 *
 * <p>Returned by {@code GET /api/paths/{id}/replay} (Step 2). Each step
 * carries both raw and LaTeX-rendered expressions plus a human-readable
 * rule explanation, suitable for the replay UI tab.</p>
 *
 * <p>Stage 3: every step additionally carries a server-side
 * {@code comparatorFlipped} flag (so the front-end and the codec agree
 * on inequality-flip detection) and {@code changedFromSpans} /
 * {@code changedToSpans} token-level diff payloads consumed by the
 * colour-diff highlighting in the replay tab.</p>
 */
public record PathReplayDto(
    String pathId,
    List<ReplayStep> steps,
    String alignedDerivationLatex,
    String alignedDerivationLatexWithDiff
) {
    public PathReplayDto {
        if (pathId == null || pathId.isBlank()) {
            throw new IllegalArgumentException("pathId is required");
        }
        steps = List.copyOf(steps);
        alignedDerivationLatex = alignedDerivationLatex == null ? "" : alignedDerivationLatex;
        alignedDerivationLatexWithDiff = alignedDerivationLatexWithDiff == null
            ? "" : alignedDerivationLatexWithDiff;
    }

    /**
     * Backward-compatible 3-arg constructor used by callers that pre-date
     * the Stage 3 diff-annotated derivation block. Derives the diff
     * block from {@code alignedDerivationLatex}'s underlying steps.
     */
    public PathReplayDto(String pathId, List<ReplayStep> steps, String alignedDerivationLatex) {
        this(pathId, steps, alignedDerivationLatex, deriveWithDiff(steps));
    }

    /**
     * Backward-compatible constructor that derives the aligned
     * derivation block from {@code steps} via {@link #MATH}.
     */
    public PathReplayDto(String pathId, List<ReplayStep> steps) {
        this(pathId, steps, derive(steps), deriveWithDiff(steps));
    }

    private static String derive(List<ReplayStep> steps) {
        return renderAligned(steps, false);
    }

    private static String deriveWithDiff(List<ReplayStep> steps) {
        return renderAligned(steps, true);
    }

    private static String renderAligned(List<ReplayStep> steps, boolean withDiff) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        List<MathPresentation.DerivationStep> derivation = new ArrayList<>(steps.size());
        for (ReplayStep step : steps) {
            derivation.add(new MathPresentation.DerivationStep(
                step.fromLatex(),
                step.toLatex(),
                step.ruleId(),
                step.comparatorFlipped(),
                step.changedFromSpans(),
                step.changedToSpans(),
                step.toExpression()
            ));
        }
        return withDiff
            ? MATH.alignedDerivationLatexWithDiff(derivation)
            : MATH.alignedDerivationLatex(derivation);
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
        boolean equivalencePreserving,
        boolean comparatorFlipped,
        List<int[]> changedFromSpans,
        List<int[]> changedToSpans
    ) {
        public ReplayStep {
            changedFromSpans = changedFromSpans == null
                ? List.of() : List.copyOf(changedFromSpans);
            changedToSpans = changedToSpans == null
                ? List.of() : List.copyOf(changedToSpans);
        }

        /**
         * Backward-compatible 9-arg constructor used by callers and codec
         * readers that pre-date the Stage 3 diff payload. Computes the
         * comparator-flip flag from the rule id and the from/to texts and
         * derives the diff spans from the from/to LaTeX strings.
         */
        public ReplayStep(
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
            this(
                stepIndex,
                fromExpression,
                fromLatex,
                toExpression,
                toLatex,
                ruleId,
                ruleExplanation,
                scoreDelta,
                equivalencePreserving,
                MathPresentation.detectComparatorFlip(ruleId, fromExpression, toExpression),
                MathDiff.diffSpans(fromLatex, toLatex).fromSpans(),
                MathDiff.diffSpans(fromLatex, toLatex).toSpans()
            );
        }

        /**
         * Stage 5 — structured {@link de.regelsuche.export.layout.MathLayout MathLayout}
         * for this step (the "to" side, which is what the UI shows for the
         * step's result row). Derived on demand from {@link #toLatex()}
         * and {@link #toExpression()}.
         */
        public de.regelsuche.export.layout.MathLayout layout() {
            return MATH.layoutWithDiff(
                fromExpression,
                toExpression,
                toLatex,
                changedToSpans
            );
        }
    }

    public static PathReplayDto from(DiscoveredTransformation path, ExplanationService explanationService) {
        List<TransformationStep> steps = path.steps();
        List<ReplayStep> replaySteps = new ArrayList<>(steps.size());
        for (TransformationStep step : steps) {
            String fromLatex = toLatex(step.beforeExpression());
            String toLatex = toLatex(step.afterExpression());
            MathDiff.Result diff = MathDiff.diffSpans(fromLatex, toLatex);
            boolean flipped = MathPresentation.detectComparatorFlip(
                step.ruleId(), step.beforeExpression(), step.afterExpression());
            replaySteps.add(new ReplayStep(
                step.index(),
                step.beforeExpression(),
                fromLatex,
                step.afterExpression(),
                toLatex,
                step.ruleId(),
                explanationService.renderStep(step, ExplanationService.Form.SCHOOL),
                step.scoreAfter() - step.scoreBefore(),
                step.equivalencePreserving(),
                flipped,
                diff.fromSpans(),
                diff.toSpans()
            ));
        }
        return new PathReplayDto(path.id(), replaySteps);
    }

    private static final MathPresentation MATH = MathPresentation.DEFAULT;

    private static String toLatex(String expression) {
        return MATH.latex(expression);
    }

    /**
     * Stage 5 — structured aligned-derivation
     * {@link de.regelsuche.export.layout.MathLayout MathLayout} for this
     * replay. Derived on demand from the contained steps via
     * {@link MathPresentation#derivationLayout(java.util.List)} so the
     * record itself stays codec-compatible while still exposing the
     * layout pipeline to layout-aware front-ends and exports.
     */
    public de.regelsuche.export.layout.MathLayout derivationLayout() {
        List<MathPresentation.DerivationStep> derivation = new ArrayList<>(steps.size());
        for (ReplayStep step : steps) {
            derivation.add(new MathPresentation.DerivationStep(
                step.fromLatex(),
                step.toLatex(),
                step.ruleId(),
                step.comparatorFlipped(),
                step.changedFromSpans(),
                step.changedToSpans(),
                step.toExpression()
            ));
        }
        return MATH.alignedDerivationLayoutWithDiff(derivation);
    }
}
