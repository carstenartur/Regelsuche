package de.regelsuche.search.moves;

import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Opens provider batches on demand. Metadata ordering never invokes an engine. */
public final class StagedMovePicker implements MovePicker {
    private record Ranked(SearchMove move, double score) {}
    private static final class Lane {
        final MoveProvider provider;
        final int stage;
        final double score;
        List<SearchMove> moves;
        int cursor;
        Lane(MoveProvider provider, int stage, double score) {
            this.provider = provider; this.stage = stage; this.score = finite(score);
        }
    }
    private final List<Lane> lanes = new ArrayList<>();
    private final List<SearchMove> generated = new ArrayList<>();
    private final MovePriorityPolicy policy;
    private final MoveState state;
    private final MoveContext context;
    private TransformationWorkMetrics work = TransformationWorkMetrics.ZERO;
    private boolean exhaustive = true;
    private int valuableBurst;

    public StagedMovePicker(List<MoveProvider> providers, MovePriorityPolicy policy, MoveState state, MoveContext context) {
        this.policy = policy; this.state = state; this.context = context;
        for (var provider : List.copyOf(providers)) lanes.add(new Lane(provider,
            policy.stage(provider.descriptor(), state, context).ordinal(), policy.providerScore(provider.descriptor(), state, context)));
        lanes.sort(Comparator.comparingInt((Lane lane) -> lane.stage).thenComparing(Comparator.comparingDouble((Lane lane) -> lane.score).reversed()));
        work = ordering(providers.size());
    }

    @Override public Optional<SearchMove> next() {
        while (!lanes.isEmpty()) {
            // A large learned batch cannot monopolize candidate consumption before primitive mathematics.
            Lane lane = valuableBurst >= 2 ? lanes.stream()
                .filter(value -> value.stage == MovePriorityPolicy.Stage.NORMAL_PRIMITIVE.ordinal()).findFirst().orElse(lanes.getFirst())
                : lanes.getFirst();
            if (lane.moves == null) {
                var batch = lane.provider.candidates(state, context);
                work = work.plus(batch.work()).plus(ordering(batch.moves().size()));
                exhaustive &= batch.complete();
                generated.addAll(batch.moves());
                var ranked = new ArrayList<Ranked>();
                for (var move : batch.moves()) {
                    move.provenance().requireSource(state.expression());
                    ranked.add(new Ranked(move, finite(policy.score(move, state, context))));
                }
                ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
                lane.moves = ranked.stream().map(Ranked::move).toList();
            }
            if (lane.cursor == lane.moves.size()) { lanes.remove(lane); continue; }
            var result = lane.moves.get(lane.cursor++);
            if (lane.stage == MovePriorityPolicy.Stage.VALUABLE_LEARNED.ordinal()) valuableBurst++;
            else if (lane.stage == MovePriorityPolicy.Stage.NORMAL_PRIMITIVE.ordinal()) valuableBurst = 0;
            return Optional.of(result);
        }
        return Optional.empty();
    }
    public int nextStage() { return lanes.isEmpty() ? 6 : lanes.getFirst().stage; }
    @Override public TransformationWorkMetrics workMetrics() { return work; }
    @Override public List<SearchMove> generatedMoves() { return List.copyOf(generated); }
    @Override public boolean complete() { return lanes.isEmpty() && exhaustive; }
    private static TransformationWorkMetrics ordering(int count) {
        return new TransformationWorkMetrics(0, 0, 0, 0, 0, 0, 0, count, 0, 0, 0, 0, 0, 0);
    }
    private static double finite(double score) {
        if (!Double.isFinite(score)) throw new IllegalArgumentException("nonfinite move priority");
        return score;
    }
}
