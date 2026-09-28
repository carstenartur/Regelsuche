package de.regelsuche.search.moves;

import de.regelsuche.retention.RetainedGraph;

import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Explicit eager control. Stable ties preserve inventory order; every score is evaluated once. */
final class EagerBatchPicker<M> implements SearchExecution.Picker<M>,RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(generated);v.reference(work);}

    private record Ranked<M>(M move, double score) {}
    private final List<M> generated;
    private final TransformationWorkMetrics work;
    private final boolean complete;
    private int cursor;

    EagerBatchPicker(List<SearchBatches.Provider<M>> providers, SearchBatches.Ranking<M> policy) {
        var ranked = new ArrayList<Ranked<M>>();
        var measured = TransformationWorkMetrics.ZERO.withDelegatedMechanicalWork(policy.contextWork());
        boolean exhaustive = true;
        for (var provider : List.copyOf(providers)) {
            var batch = provider.candidates();
            measured = measured.plus(batch.work());
            exhaustive &= batch.complete();
            for (var move : batch.moves()) {
                policy.requireSource(move);
                double score = policy.score(move);
                if (!Double.isFinite(score)) throw new IllegalArgumentException("nonfinite move priority");
                ranked.add(new Ranked<>(move, score));
            }
        }
        ranked.sort(Comparator.<Ranked<M>>comparingDouble(Ranked::score).reversed());
        generated = ranked.stream().map(Ranked::move).toList();
        work = measured.plus(new TransformationWorkMetrics(0, 0, 0, 0, 0, 0, 0,
            ranked.size(), 0, 0, 0, 0, 0, 0));
        complete = exhaustive;
    }
    @Override public Optional<M> next() { return cursor == generated.size() ? Optional.empty() : Optional.of(generated.get(cursor++)); }
    @Override public TransformationWorkMetrics workMetrics() { return work; }
    @Override public List<M> generatedMoves() { return generated; }
    @Override public boolean complete() { return complete; }
}
