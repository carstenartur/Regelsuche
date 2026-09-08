package de.regelsuche.transform;

import de.regelsuche.assumption.AssumptionSignature;
import java.util.List;

public record Transformation(
    String rule,
    String transformedExpression,
    RewriteKind kind,
    boolean mayIncreaseComplexity,
    int estimatedCostDelta,
    boolean equivalencePreservingByConstruction,
    String applicationKey,
    List<String> assumptions,
    String packId,
    String license,
    List<String> primitiveRuleIds,
    TransformationProvenance provenance
) {
    public Transformation(String rule, String transformedExpression) {
        this(
            rule,
            transformedExpression,
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            rule + ":" + transformedExpression);
    }

    public Transformation(
        String rule,
        String transformedExpression,
        RewriteKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreservingByConstruction,
        String applicationKey
    ) {
        this(
            rule,
            transformedExpression,
            kind,
            mayIncreaseComplexity,
            estimatedCostDelta,
            equivalencePreservingByConstruction,
            applicationKey,
            List.of());
    }

    public Transformation(
        String rule,
        String transformedExpression,
        RewriteKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreservingByConstruction,
        String applicationKey,
        List<String> assumptions
    ) {
        this(
            rule,
            transformedExpression,
            kind,
            mayIncreaseComplexity,
            estimatedCostDelta,
            equivalencePreservingByConstruction,
            applicationKey,
            assumptions,
            "core",
            "PROJECT");
    }

    /** Compatibility constructor: one ordinary transformation is one primitive. */
    public Transformation(
        String rule,
        String transformedExpression,
        RewriteKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreservingByConstruction,
        String applicationKey,
        List<String> assumptions,
        String packId,
        String license
    ) {
        this(
            rule,
            transformedExpression,
            kind,
            mayIncreaseComplexity,
            estimatedCostDelta,
            equivalencePreservingByConstruction,
            applicationKey,
            assumptions,
            packId,
            license,
            List.of(rule));
    }

    public Transformation(String rule, String transformedExpression, RewriteKind kind,
                          boolean mayIncreaseComplexity, int estimatedCostDelta,
                          boolean equivalencePreservingByConstruction, String applicationKey,
                          List<String> assumptions, String packId, String license, List<String> primitiveRuleIds) {
        this(rule, transformedExpression, kind, mayIncreaseComplexity, estimatedCostDelta,
            equivalencePreservingByConstruction, applicationKey, assumptions, packId, license, primitiveRuleIds,
            new TransformationProvenance.PrimitiveRewriteSequence(primitiveRuleIds, applicationKey));
    }

    public static Transformation exactTheory(ExactTheoryEvidence evidence) {
        var provenance = new TransformationProvenance.ExactTheoryStep(evidence);
        var binding = evidence.binding();
        return new Transformation(binding.theoryStepId(), binding.transformedExpression(), RewriteKind.NORMALIZE,
            true, 0, true, "execution:" + provenance.contentHash(), List.of(), "exact-theory", "PROJECT",
            List.of(), provenance);
    }

    public Transformation {
        if (rule == null || rule.isBlank()
                || transformedExpression == null
                || transformedExpression.isBlank()
                || kind == null
                || applicationKey == null
                || applicationKey.isBlank()) {
            throw new IllegalArgumentException(
                "rule, kind, applicationKey and transformedExpression "
                    + "must not be blank");
        }
        assumptions = AssumptionSignature.ofExpressions(assumptions)
            .normalizedAssumptions();
        packId = packId == null || packId.isBlank() ? "core" : packId;
        license = license == null || license.isBlank() ? "PROJECT" : license;
        if (primitiveRuleIds == null || provenance == null) {
            throw new IllegalArgumentException(
                "primitiveRuleIds and provenance must be present");
        }
        List<String> retainedRules = provenance.primitiveRuleIds();
        // The common constructor already normalized and froze this list in
        // primitive provenance. Reuse it instead of allocating another stream/list.
        primitiveRuleIds = primitiveRuleIds.equals(retainedRules) ? retainedRules : primitiveRuleIds.stream()
            .map(value -> {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(
                        "primitive rule IDs must not be blank");
                }
                return value.trim();
            })
            .toList();
        if (!primitiveRuleIds.equals(retainedRules)) {
            throw new IllegalArgumentException("primitive rule list differs from provenance");
        }
        requireBoundProvenance(provenance, transformedExpression, applicationKey, assumptions,
            equivalencePreservingByConstruction);
        if (!(provenance instanceof TransformationProvenance.PrimitiveRewriteSequence)
                && provenance.work().exactTheorySteps() > 0) {
            applicationKey = "execution:" + provenance.contentHash();
        }
    }

    private static void requireBoundProvenance(TransformationProvenance provenance,
            String transformedExpression, String applicationKey, List<String> assumptions,
            boolean equivalencePreservingByConstruction) {
        if (provenance instanceof TransformationProvenance.PrimitiveRewriteSequence primitive
                && !primitive.applicationKey().equals(applicationKey)) {
            throw new IllegalArgumentException("primitive application identity differs from provenance");
        }
        if (provenance instanceof TransformationProvenance.ExactTheoryStep theory) {
            if (!transformedExpression.equals(theory.evidence().binding().transformedExpression())
                    || !assumptions.isEmpty() || !equivalencePreservingByConstruction) {
                throw new IllegalArgumentException("transformation differs from its verified theory evidence");
            }
        }
        if (provenance instanceof TransformationProvenance.Sequence sequence) {
            var boundAssumptions = AssumptionSignature.ofExpressions(sequence.steps().stream()
                .flatMap(step -> step.assumptions().stream()).toList()).normalizedAssumptions();
            if (!transformedExpression.equals(sequence.steps().getLast().transformedExpression())
                    || !assumptions.equals(boundAssumptions)
                    || equivalencePreservingByConstruction != sequence.steps().stream()
                        .allMatch(Transformation::equivalencePreservingByConstruction)) {
                throw new IllegalArgumentException("transformation differs from its retained sequence");
            }
        }
    }

    /** Number of actual primitive rewrites represented by this search edge. */
    public int primitiveStepCount() {
        return primitiveRuleIds.size();
    }

    public ExecutionWork executionWork() { return provenance.work(); }

    public long exactTheoryStepCount() {
        return switch (provenance) {
            case TransformationProvenance.PrimitiveRewriteSequence ignored -> 0L;
            case TransformationProvenance.ExactTheoryStep ignored -> 1L;
            case TransformationProvenance.Sequence sequence -> sequence.steps().stream()
                .mapToLong(Transformation::exactTheoryStepCount).reduce(0L, Math::addExact);
        };
    }

    /** Guard for legacy consumers whose budgets and replay understand only primitives. */
    public static List<Transformation> requirePrimitiveOnly(List<Transformation> transformations) {
        for (Transformation transformation : transformations) {
            if (transformation.exactTheoryStepCount() != 0) {
                throw new IllegalArgumentException("exact theory requires an explicitly work-aware execution boundary");
            }
        }
        return transformations;
    }
}
