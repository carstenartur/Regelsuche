package de.regelsuche.search.moves;

import static de.regelsuche.search.moves.IncrementalProviderContract.*;

import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Stage selection inside IncrementalMovePicker; provider progress remains attached to its parent expansion. */
final class StagedIncrementalLanes<M,S> implements SearchExecution.Picker<M> {
    interface Source<M> {
        MoveProvider.Descriptor descriptor();
        boolean batch();
        ObjectCursor<M> open(java.util.function.Consumer<List<M>> generated,java.util.function.LongSupplier totalWork);
    }
    private final class Lane {
        final Source<M> provider;
        final int index, stage;
        final double score;
        ObjectCursor<M> cursor;
        boolean finished;
        Lane(Source<M> provider, int index, SearchBatches.Ranking<M> ranking) {
            this.provider = provider; this.index = index;
            stage = ranking.stage(provider.descriptor());
            score = ranking.providerScore(provider.descriptor());
            if (!Double.isFinite(score)) throw new IllegalArgumentException("nonfinite provider priority");
        }
    }
    private final List<Lane> lanes = new ArrayList<>();
    private final List<M> generated = new ArrayList<>();
    private final S state;
    private final TransformationWorkMetrics ordering;
    private int learnedBurst;
    private boolean closed, workExhausted;

    StagedIncrementalLanes(List<Source<M>> providers,SearchBatches.Ranking<M> ranking,S state) {
        this.state=state;
        var inventory = List.copyOf(providers);
        for (int i = 0; i < inventory.size(); i++) {
            var provider = inventory.get(i);
            lanes.add(new Lane(provider,i,ranking));
        }
        lanes.sort(Comparator.comparingInt((Lane lane) -> lane.stage)
            .thenComparing(Comparator.comparingDouble((Lane lane) -> lane.score).reversed()));
        ordering = new TransformationWorkMetrics(0, 0, 0, 0, 0, 0, 0, lanes.size(), 0, 0, 0, 0, 0, 0)
            .withDelegatedMechanicalWork(ranking.contextWork());
    }
    @Override public Optional<M> next(){return next(Long.MAX_VALUE);}
    @Override public boolean incremental(){return true;}
    @Override public boolean accountingComplete(){return lanes.stream().allMatch(lane->lane.cursor==null || lane.cursor.snapshot().accountingComplete());}
    @Override public Object executionReceipt(){return receipt();}
    public Optional<M> next(long allowance) {
        if (allowance < 0) throw new IllegalArgumentException("negative move allowance");
        if (closed) return Optional.empty();
        workExhausted = false;
        long before = workMetrics().totalWorkUnitsV2();
        for (Lane lane = selected(); lane != null; lane = selected()) {
            long remaining = Math.max(0, allowance - (workMetrics().totalWorkUnitsV2() - before));
            if (remaining == 0) { workExhausted = true; return Optional.empty(); }
            if (lane.cursor == null) open(lane);
            var candidate = lane.cursor.next(remaining);
            if (candidate.isPresent()) return emit(lane, candidate.orElseThrow());
            if (lane.cursor.snapshot().resumable()) { workExhausted = true; return Optional.empty(); }
            if (lane.cursor.snapshot().status() == Status.READY) continue;
            lane.cursor.close(); lane.finished = true;
        }
        return Optional.empty();
    }
    private void open(Lane lane) {
        lane.cursor=lane.provider.open(generated::addAll,()->workMetrics().totalWorkUnits());
    }
    private Optional<M> emit(Lane lane,M move) {
        if(!lane.provider.batch())generated.add(move);
        if(lane.provider.descriptor().sourceKind()==SearchMove.SourceKind.LEARNED)learnedBurst++;
        else if(lane.provider.descriptor().sourceKind()==SearchMove.SourceKind.PRIMITIVE)learnedBurst=0;
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
    public int nextStage() { var lane = selected(); return lane == null ? MovePriorityPolicy.Stage.values().length : lane.stage; }
    public TransformationWorkMetrics workMetrics() {
        var work = ordering;
        for (var lane : lanes) if (lane.cursor != null) work = work.plus(lane.cursor.snapshot().work().metrics());
        return work;
    }
    public List<M> generatedMoves() { return List.copyOf(generated); }
    public boolean complete() { return lanes.stream().allMatch(lane -> lane.finished && lane.cursor.snapshot().complete()); }
    public boolean workExhausted() { return workExhausted; }
    SearchExecution.Expansion<S> receipt() {
        return new SearchExecution.Expansion<>(state, closed, lanes.stream().map(lane ->
            new StagedIncrementalMoveExecution.Lane(lane.index, lane.stage, lane.cursor == null ? null : lane.cursor.snapshot())).toList());
    }
    public void close() {
        if (closed) return;
        closed = true;
        for (var lane : lanes) if (lane.cursor != null) lane.cursor.close();
    }
}
