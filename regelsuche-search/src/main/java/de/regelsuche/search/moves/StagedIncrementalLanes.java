package de.regelsuche.search.moves;

import static de.regelsuche.search.moves.IncrementalProviderContract.*;

import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Stage selection inside IncrementalMovePicker; provider progress remains attached to its parent expansion. */
final class StagedIncrementalLanes {
    private static final class Lane {
        final MoveProvider provider;
        final int index, stage;
        final double score;
        Cursor cursor;
        boolean finished;
        Lane(MoveProvider provider, int index, MovePriorityPolicy policy, MoveState state, MoveContext context) {
            this.provider = provider; this.index = index;
            stage = policy.stage(provider.descriptor(), state, context).ordinal();
            score = policy.providerScore(provider.descriptor(), state, context);
            if (!Double.isFinite(score)) throw new IllegalArgumentException("nonfinite provider priority");
        }
    }
    private final List<Lane> lanes = new ArrayList<>();
    private final List<SearchMove> generated = new ArrayList<>();
    private final MoveState state;
    private final MoveContext context;
    private final TransformationWorkMetrics ordering;
    private int learnedBurst;
    private boolean closed, workExhausted;

    StagedIncrementalLanes(List<MoveProvider> providers, MovePriorityPolicy policy, MoveState state, MoveContext context) {
        this.state = state; this.context = context;
        var inventory = List.copyOf(providers);
        for (int i = 0; i < inventory.size(); i++) {
            var provider = inventory.get(i);
            if (!StagedIncrementalSources.supported(provider)) throw new IllegalArgumentException("unsupported staged incremental provider");
            lanes.add(new Lane(provider, i, policy, state, context));
        }
        lanes.sort(Comparator.comparingInt((Lane lane) -> lane.stage)
            .thenComparing(Comparator.comparingDouble((Lane lane) -> lane.score).reversed()));
        ordering = new TransformationWorkMetrics(0, 0, 0, 0, 0, 0, 0, lanes.size(), 0, 0, 0, 0, 0, 0)
            .withDelegatedMechanicalWork(policy.contextWork(state, context));
    }
    Optional<SearchMove> next(long allowance) {
        if (allowance < 0) throw new IllegalArgumentException("negative move allowance");
        if (closed) return Optional.empty();
        workExhausted = false;
        long before = workMetrics().totalWorkUnitsV2();
        for (Lane lane = selected(); lane != null; lane = selected()) {
            long remaining = Math.max(0, allowance - (workMetrics().totalWorkUnitsV2() - before));
            if (remaining == 0) { workExhausted = true; return Optional.empty(); }
            if (lane.cursor == null) lane.cursor = StagedIncrementalSources.open(lane.provider, state, context, generated::addAll);
            var candidate = lane.cursor.next(remaining);
            if (candidate.isPresent()) return emit(lane, candidate.orElseThrow());
            if (lane.cursor.snapshot().resumable()) { workExhausted = true; return Optional.empty(); }
            if (lane.cursor.snapshot().status() == Status.READY) continue;
            lane.cursor.close(); lane.finished = true;
        }
        return Optional.empty();
    }
    private Optional<SearchMove> emit(Lane lane, de.regelsuche.transform.Transformation candidate) {
        var move = SearchMove.from(candidate, lane.provider.descriptor(), workMetrics().totalWorkUnits());
        if (!StagedIncrementalSources.typedBatch(lane.provider)) generated.add(move);
        if (lane.provider.descriptor().sourceKind() == SearchMove.SourceKind.LEARNED) learnedBurst++;
        else if (lane.provider.descriptor().sourceKind() == SearchMove.SourceKind.PRIMITIVE) learnedBurst = 0;
        return Optional.of(move);
    }
    private Lane selected() {
        var active = lanes.stream().filter(lane -> !lane.finished).toList();
        if (learnedBurst >= 2) {
            var primitive = active.stream().filter(lane -> lane.provider.descriptor().sourceKind() == SearchMove.SourceKind.PRIMITIVE).findFirst();
            if (primitive.isPresent()) return primitive.orElseThrow();
        }
        return active.isEmpty() ? null : active.getFirst();
    }
    int nextStage() { var lane = selected(); return lane == null ? MovePriorityPolicy.Stage.values().length : lane.stage; }
    TransformationWorkMetrics workMetrics() {
        var work = ordering;
        for (var lane : lanes) if (lane.cursor != null) work = work.plus(lane.cursor.snapshot().work().metrics());
        return work;
    }
    List<SearchMove> generatedMoves() { return List.copyOf(generated); }
    boolean complete() { return lanes.stream().allMatch(lane -> lane.finished && lane.cursor.snapshot().complete()); }
    boolean workExhausted() { return workExhausted; }
    StagedIncrementalMoveExecution.Expansion receipt() {
        return new StagedIncrementalMoveExecution.Expansion(state, closed, lanes.stream().map(lane ->
            new StagedIncrementalMoveExecution.Lane(lane.index, lane.stage, lane.cursor == null ? null : lane.cursor.snapshot())).toList());
    }
    void close() {
        if (closed) return;
        closed = true;
        for (var lane : lanes) if (lane.cursor != null) lane.cursor.close();
    }
}
