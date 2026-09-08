package de.regelsuche.transform;

/** Primitive-only engine that selects candidates with the remaining path depth in view. */
public interface PrimitiveBudgetedTransformationEngine extends MeasuredTransformationEngine {
    TransformationBatch transformMeasured(String expression, long remainingPrimitiveSteps);

    @Override
    default TransformationBatch transformMeasured(String expression) {
        return transformMeasured(expression, Long.MAX_VALUE);
    }
}
