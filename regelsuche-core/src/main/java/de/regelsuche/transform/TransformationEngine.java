package de.regelsuche.transform;

import java.util.List;

public interface TransformationEngine {
    List<Transformation> transform(String expression);

    /**
     * Whether the returned candidate order is part of this engine's explicit
     * search policy and therefore must not be re-sorted by the search strategy.
     * Ordinary engines keep the deterministic Best-First ordering.
     */
    default boolean providesCandidateOrder() {
        return false;
    }
}
