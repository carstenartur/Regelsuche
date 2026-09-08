package de.regelsuche.transform;

import java.util.List;

/** A source that must never be invoked through an unbudgeted engine boundary. */
public interface WorkAwareTransformationEngine extends TransformationEngine {
    List<Transformation> verifiedTransformations(String expression);

    @Override default List<Transformation> transform(String expression) {
        throw new IllegalArgumentException("this source requires explicit primitive and theory path budgets");
    }
}
