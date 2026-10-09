package de.regelsuche.search.moves;

import de.regelsuche.retention.RetainedOperation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable shared witness prefixes; appending never copies the complete ancestral path. */
final class MoveWitnessPath<S,M,V> implements de.regelsuche.retention.RetainedGraph.View {
    @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(parent);v.reference(step);}
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
        Object[] pending = new Object[1];
        var retained = RetainedOperation.retainCompleted(2, this, steps, pending);
        Throwable primary = null;
        try {
            for (var cursor = this; cursor.step != null; cursor = cursor.parent) {
                steps.add(cursor.step);
                SearchExecution.completed(1);
            }
            Collections.reverse(steps);
            SearchExecution.completed(steps.size() / 2L);
            return SearchExecution.copied(pending, 0, List.copyOf(steps), steps, steps.size());
        } catch (RuntimeException | Error failure) {
            primary = failure;
            SearchExecution.observeFailure(failure);
            throw failure;
        } finally { SearchExecution.close(retained, primary); }
    }
}
