package de.regelsuche.search.moves;

import de.regelsuche.transform.MeasuredTransformationEngines;
import de.regelsuche.transform.TransformationEngine;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.List;
import java.util.Objects;

/** Adapts candidate formation while preserving its measured batch work and typed provenance. */
public record EngineMoveProvider(Descriptor descriptor, TransformationEngine engine,
                                 boolean completeRelation) implements MoveProvider {
    public EngineMoveProvider { Objects.requireNonNull(descriptor); Objects.requireNonNull(engine); }
    @Override public Batch candidates(MoveState state, MoveContext context) {
        if (!context.carries(descriptor.requiredAssumptions(), state)) return new Batch(List.of(),
            new TransformationWorkMetrics(0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0), true);
        var batch = MeasuredTransformationEngines.counting(engine).transformMeasured(state.expression());
        var work = batch.workMetrics().plus(new TransformationWorkMetrics(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0));
        return new Batch(batch.transformations().stream()
            .map(step -> SearchMove.from(step, descriptor, work.totalWorkUnits())).toList(), work, completeRelation);
    }
}
