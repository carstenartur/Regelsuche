package de.regelsuche.search.moves;

import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Explicit eager control. Stable ties preserve inventory order; every score is evaluated once. */
public final class EagerMovePicker implements MovePicker {
    private record Ranked(SearchMove move, double score) {}
    private final List<SearchMove> generated;
    private final TransformationWorkMetrics work;
    private final boolean complete;
    private int cursor;

    public EagerMovePicker(List<MoveProvider> providers, MovePriorityPolicy policy, MoveState state, MoveContext context) {
        var ranked = new ArrayList<Ranked>();
        var measured = TransformationWorkMetrics.ZERO;
        boolean exhaustive = true;
        for (var provider : List.copyOf(providers)) {
            var batch = provider.candidates(state, context);
            measured = measured.plus(batch.work());
            exhaustive &= batch.complete();
            for (var move : batch.moves()) {
                move.provenance().requireSource(state.expression());
                double score = policy.score(move, state, context);
                if (!Double.isFinite(score)) throw new IllegalArgumentException("nonfinite move priority");
                ranked.add(new Ranked(move, score));
            }
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
        generated = ranked.stream().map(Ranked::move).toList();
        work = measured.plus(new TransformationWorkMetrics(0, 0, 0, 0, 0, 0, 0,
            ranked.size(), 0, 0, 0, 0, 0, 0));
        complete = exhaustive;
    }
    @Override public Optional<SearchMove> next() { return cursor == generated.size() ? Optional.empty() : Optional.of(generated.get(cursor++)); }
    @Override public TransformationWorkMetrics workMetrics() { return work; }
    @Override public List<SearchMove> generatedMoves() { return generated; }
    @Override public boolean complete() { return complete; }
}
