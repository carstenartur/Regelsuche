package de.regelsuche.transform;

import java.util.List;
import java.util.Objects;

public interface TransformationEngine {
    List<Transformation> transform(String expression);

    /**
     * Decorates application keys with the concrete result state.
     *
     * <p>This lets search apply one rule to two equal but distinct occurrences:
     * the source-subtree identity may be equal, while the resulting whole
     * expressions identify different transitions. Truly duplicate transitions
     * still receive the same key.</p>
     */
    static TransformationEngine withTransitionIdentity(TransformationEngine delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return expression -> delegate.transform(expression).stream()
            .map(TransformationEngine::withTransitionIdentity)
            .toList();
    }

    private static Transformation withTransitionIdentity(Transformation transformation) {
        String key = transformation.applicationKey()
            + "->" + transformation.transformedExpression();
        return new Transformation(
            transformation.rule(),
            transformation.transformedExpression(),
            transformation.kind(),
            transformation.mayIncreaseComplexity(),
            transformation.estimatedCostDelta(),
            transformation.equivalencePreservingByConstruction(),
            key,
            transformation.assumptions(),
            transformation.packId(),
            transformation.license());
    }
}
