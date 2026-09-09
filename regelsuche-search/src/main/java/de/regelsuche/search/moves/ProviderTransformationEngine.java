package de.regelsuche.search.moves;

import de.regelsuche.transform.MeasuredTransformationEngine;
import de.regelsuche.transform.TransformationBatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Opt-in bridge for existing consumers; search callers can pull evidence-bearing moves directly. */
public record ProviderTransformationEngine(List<MoveProvider> moveProviders, MovePriorityPolicy policy,
                                            MoveContext context) implements MeasuredTransformationEngine, MoveProviderInventory {
    public ProviderTransformationEngine {
        moveProviders = List.copyOf(moveProviders);
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(context, "context");
        if (context.phase() == MoveContext.Phase.PRODUCTION) {
            throw new IllegalArgumentException("experimental move scheduling requires separate production qualification (#745)");
        }
    }
    public MovePicker picker(MoveState state) { return new EagerMovePicker(moveProviders, policy, state, context); }
    @Override public TransformationBatch transformMeasured(String expression) {
        var picker = picker(MoveState.root(expression));
        var steps = new ArrayList<de.regelsuche.transform.Transformation>();
        for (var next = picker.next(); next.isPresent(); next = picker.next()) steps.add(next.orElseThrow().transformation());
        return new TransformationBatch(steps, picker.workMetrics());
    }
}
