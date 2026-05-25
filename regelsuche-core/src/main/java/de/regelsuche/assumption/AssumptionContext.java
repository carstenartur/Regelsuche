package de.regelsuche.assumption;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Accumulates {@link Assumption}s gathered along a transformation path.
 *
 * <p>The context dedupes assumptions by their expression text so a rule that
 * fires multiple times along a path only contributes its assumption once.</p>
 */
public final class AssumptionContext {
    private final Set<String> known = new LinkedHashSet<>();
    private final List<Assumption> assumptions = new ArrayList<>();

    public void add(Assumption assumption) {
        if (assumption == null) {
            return;
        }
        if (known.add(assumption.expression())) {
            assumptions.add(assumption);
        }
    }

    public void addAll(List<Assumption> additions) {
        if (additions == null) {
            return;
        }
        for (Assumption assumption : additions) {
            add(assumption);
        }
    }

    /** @return immutable snapshot of the accumulated assumptions. */
    public List<Assumption> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(assumptions));
    }

    public boolean isEmpty() {
        return assumptions.isEmpty();
    }

    @Override
    public String toString() {
        return assumptions.toString();
    }
}
