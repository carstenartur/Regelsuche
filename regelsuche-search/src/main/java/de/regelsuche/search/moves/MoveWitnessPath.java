package de.regelsuche.search.moves;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable shared witness prefixes; appending never copies the complete ancestral path. */
final class MoveWitnessPath {
    static final MoveWitnessPath ROOT = new MoveWitnessPath(null, null);
    private final MoveWitnessPath parent;
    private final MoveSearch.WitnessStep step;
    private MoveWitnessPath(MoveWitnessPath parent, MoveSearch.WitnessStep step) {
        this.parent = parent;
        this.step = step;
    }
    MoveWitnessPath append(MoveSearch.WitnessStep next) {
        return new MoveWitnessPath(this, java.util.Objects.requireNonNull(next));
    }
    List<MoveSearch.WitnessStep> steps() {
        var steps = new ArrayList<MoveSearch.WitnessStep>();
        for (var cursor = this; cursor.step != null; cursor = cursor.parent) steps.add(cursor.step);
        Collections.reverse(steps);
        return List.copyOf(steps);
    }
}
