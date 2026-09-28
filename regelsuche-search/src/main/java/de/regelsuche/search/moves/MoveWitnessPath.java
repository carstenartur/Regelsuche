package de.regelsuche.search.moves;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable shared witness prefixes; appending never copies the complete ancestral path. */
final class MoveWitnessPath<S,M,V> {
    static <S,M,V> MoveWitnessPath<S,M,V> root() { return new MoveWitnessPath<>(null,null); }
    private final MoveWitnessPath<S,M,V> parent;
    private final SearchExecution.Step<S,M,V> step;
    private MoveWitnessPath(MoveWitnessPath<S,M,V> parent, SearchExecution.Step<S,M,V> step) {
        this.parent = parent;
        this.step = step;
    }
    MoveWitnessPath<S,M,V> append(SearchExecution.Step<S,M,V> next) {
        return new MoveWitnessPath<>(this, java.util.Objects.requireNonNull(next));
    }
    List<SearchExecution.Step<S,M,V>> steps() {
        var steps = new ArrayList<SearchExecution.Step<S,M,V>>();
        for (var cursor = this; cursor.step != null; cursor = cursor.parent) steps.add(cursor.step);
        Collections.reverse(steps);
        return List.copyOf(steps);
    }
}
