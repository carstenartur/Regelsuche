package de.regelsuche.transform;

import java.util.List;
import java.util.Objects;

/** One deterministic transformation result batch and its complete work ledger. */
public record TransformationBatch(
    List<Transformation> transformations,
    TransformationWorkMetrics workMetrics
) {
    public TransformationBatch {
        transformations = transformations == null
            ? List.of()
            : transformations.stream()
                .map(value -> Objects.requireNonNull(
                    value, "transformation"))
                .toList();
        workMetrics = workMetrics == null
            ? TransformationWorkMetrics.ZERO
            : workMetrics;
    }
}
