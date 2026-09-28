package de.regelsuche.assumption;

import java.util.ArrayList;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Accumulates {@link Assumption}s gathered along a transformation path.
 *
 * <p>The context dedupes assumptions by their expression text so a rule that
 * fires multiple times along a path only contributes its assumption once.</p>
 */
public final class AssumptionContext implements RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(known); visitor.reference(assumptions); }
    private final Set<String> known = new LinkedHashSet<>();
    private final List<Assumption> assumptions = new ArrayList<>();

    public void add(Assumption assumption) {
        if (assumption == null) {
            return;
        }
        try (var owned = RetainedOperation.retain(this, assumption)) {
            boolean added = known.add(assumption.expression());
            RetainedOperation.work(1);
            if (added) {
                assumptions.add(assumption);
                RetainedOperation.work(1);
            }
            RetainedOperation.checkpoint();
        }
    }

    public void addAll(List<Assumption> additions) {
        if (additions == null) {
            return;
        }
        for (Assumption assumption : additions) {
            RetainedOperation.work(1);
            add(assumption);
        }
    }

    /** @return immutable snapshot of the accumulated assumptions. */
    public List<Assumption> snapshot() {
        try (var owned = RetainedOperation.retain(this)) {
            var snapshot = List.copyOf(assumptions);
            RetainedOperation.work(assumptions.size());
            return RetainedOperation.produced(snapshot);
        }
    }

    public boolean isEmpty() {
        return assumptions.isEmpty();
    }

    @Override
    public String toString() {
        return assumptions.toString();
    }
}
