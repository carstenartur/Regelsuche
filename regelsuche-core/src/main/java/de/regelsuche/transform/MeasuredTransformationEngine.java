package de.regelsuche.transform;

import java.util.List;

/** Transformation engine that exposes deterministic work accounting. */
public interface MeasuredTransformationEngine extends TransformationEngine {
    TransformationBatch transformMeasured(String expression);

    @Override
    default List<Transformation> transform(String expression) {
        return transformMeasured(expression).transformations();
    }
}
