package de.regelsuche.search.moves;

import de.regelsuche.retention.RetainedGraph;

import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Opens provider batches on demand. Metadata ordering never invokes an engine. */
final class StagedBatchPicker<M> implements SearchExecution.Picker<M>,RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(lanes);v.reference(primitiveLanes);v.reference(generated);v.reference(policy);v.reference(work);}

    private static final int EXHAUSTED_STAGE = MovePriorityPolicy.Stage.values().length;
    private record Ranked<M>(M move, double score) {}
    private final class Lane implements RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(StagedBatchPicker.this);v.reference(provider);v.reference(moves);}

        final SearchBatches.Provider<M> provider;
        final int stage;
        final double score;
        List<M> moves;
        int cursor;
        Lane(SearchBatches.Provider<M> provider, int stage, double score) {
            this.provider = provider; this.stage = stage; this.score = finite(score);
        }
    }
    private final List<Lane> lanes = new ArrayList<>();
    private final java.util.ArrayDeque<Lane> primitiveLanes = new java.util.ArrayDeque<>();
    private final List<M> generated = new ArrayList<>();
    private final SearchBatches.Ranking<M> policy;
    private TransformationWorkMetrics work = TransformationWorkMetrics.ZERO;
    private boolean exhaustive = true;
    private int valuableBurst;

    StagedBatchPicker(List<SearchBatches.Provider<M>> providers, SearchBatches.Ranking<M> policy) {
        this.policy = policy;
        for (var provider : List.copyOf(providers)) lanes.add(new Lane(provider,
            policy.stage(provider.descriptor()), policy.providerScore(provider.descriptor())));
        lanes.sort(Comparator.comparingInt((Lane lane) -> lane.stage).thenComparing(Comparator.comparingDouble((Lane lane) -> lane.score).reversed()));
        lanes.stream().filter(lane -> lane.provider.descriptor().sourceKind() == SearchMove.SourceKind.PRIMITIVE).forEach(primitiveLanes::addLast);
        work = ordering(providers.size()).withDelegatedMechanicalWork(policy.contextWork());
    }

    @Override public Optional<M> next() {
        while (!lanes.isEmpty()) {
            // A large learned batch cannot monopolize candidate consumption before primitive mathematics.
            Lane lane = valuableBurst >= 2 && !primitiveLanes.isEmpty() ? primitiveLanes.getFirst()
                : lanes.getFirst();
            if (lane.moves == null) {
                var batch = lane.provider.candidates();
                work = work.plus(batch.work()).plus(ordering(batch.moves().size()));
                exhaustive &= batch.complete();
                generated.addAll(batch.moves());
                var ranked = new ArrayList<Ranked<M>>();
                for (var move : batch.moves()) {
                    policy.requireSource(move);
                    ranked.add(new Ranked<>(move, finite(policy.score(move))));
                }
                ranked.sort(Comparator.<Ranked<M>>comparingDouble(Ranked::score).reversed());
                lane.moves = ranked.stream().map(Ranked::move).toList();
            }
            if (lane.cursor == lane.moves.size()) {
                lanes.remove(lane);
                if (primitiveLanes.peekFirst() == lane) primitiveLanes.removeFirst();
                continue;
            }
            var result = lane.moves.get(lane.cursor++);
            if (lane.provider.descriptor().sourceKind() == SearchMove.SourceKind.LEARNED) valuableBurst++;
            else if (lane.provider.descriptor().sourceKind() == SearchMove.SourceKind.PRIMITIVE) valuableBurst = 0;
            return Optional.of(result);
        }
        return Optional.empty();
    }
    public int nextStage() { return lanes.isEmpty() ? EXHAUSTED_STAGE : lanes.getFirst().stage; }
    @Override public TransformationWorkMetrics workMetrics() { return work; }
    @Override public List<M> generatedMoves() { return List.copyOf(generated); }
    @Override public boolean complete() { return lanes.isEmpty() && exhaustive; }
    private static TransformationWorkMetrics ordering(int count) {
        return new TransformationWorkMetrics(0, 0, 0, 0, 0, 0, 0, count, 0, 0, 0, 0, 0, 0);
    }
    private static double finite(double score) {
        if (!Double.isFinite(score)) throw new IllegalArgumentException("nonfinite move priority");
        return score;
    }
}
