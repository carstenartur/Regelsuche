package de.regelsuche.search.moves;

import de.regelsuche.retention.RetainedGraph;

import static de.regelsuche.search.moves.IncrementalProviderContract.*;

import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Stage selection inside IncrementalMovePicker; provider progress remains attached to its parent expansion. */
final class StagedIncrementalLanes<M,S> implements SearchExecution.Picker<M>,RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(lanes);v.reference(generated);v.reference(state);v.reference(ordering);v.reference(batchReceipts);}

    interface Source<M> extends RetainedGraph.View {
        @Override default void retainedReferences(RetainedGraph.Visitor v){v.requireExact(this,Void.class);}
        MoveProvider.Descriptor descriptor();
        boolean batch();
        default boolean nativeTransport(){return false;}
        ObjectCursor<M> open(java.util.function.Consumer<List<M>> generated,java.util.function.LongSupplier totalWork);
    }
    private final class Lane implements RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(StagedIncrementalLanes.this);v.reference(provider);v.reference(cursor);}

        final Source<M> provider;
        final int index, stage;
        final double score;
        ObjectCursor<M> cursor;
        boolean finished;
        int capturedBatchReceipts;
        Lane(Source<M> provider, int index, SearchBatches.Ranking<M> ranking) {
            this.provider = provider; this.index = index;
            stage = ranking.stage(provider.descriptor());
            score = ranking.providerScore(provider.descriptor());
            if (!Double.isFinite(score)) throw new IllegalArgumentException("nonfinite provider priority");
        }
    }
    private final List<Lane> lanes = new ArrayList<>();
    private final List<M> generated = new ArrayList<>();
    private final List<Snapshot> batchReceipts = new ArrayList<>();
    private final S state;
    private final TransformationWorkMetrics ordering;
    private int learnedBurst;
    private boolean closed, workExhausted;
    private final boolean nativeObservation;

    StagedIncrementalLanes(List<Source<M>> providers,SearchBatches.Ranking<M> ranking,S state) {
        this.state=state;
        nativeObservation=providers.stream().anyMatch(Source::nativeTransport);
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
        long before = pullWork();
        for (Lane lane = selected(); lane != null; lane = selected()) {
            long remaining = Math.max(0, allowance - (pullWork() - before));
            if (remaining == 0) { workExhausted = true; return Optional.empty(); }
            if (lane.cursor == null) {
                open(lane);
                remaining = Math.max(0, allowance - (pullWork() - before));
                if (remaining == 0) { workExhausted = true; return Optional.empty(); }
            }
            var candidate = lane.cursor.next(remaining);
            if (candidate.isPresent()) return emit(lane, candidate.orElseThrow());
            if (lane.cursor.snapshot().resumable()) { workExhausted = true; return Optional.empty(); }
            if (lane.cursor.snapshot().status() == Status.READY) continue;
            lane.cursor.close(); lane.finished = true;
        }
        return Optional.empty();
    }
    private final class Generated implements java.util.function.Consumer<List<M>>,RetainedGraph.View {
        @Override public void accept(List<M> moves){generated.addAll(moves);}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(StagedIncrementalLanes.this);}
    }
    private final class TotalWork implements java.util.function.LongSupplier,RetainedGraph.View {
        @Override public long getAsLong(){return workMetrics().totalWorkUnits();}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(StagedIncrementalLanes.this);}
    }
    private void open(Lane lane) {
        lane.cursor=lane.provider.open(new Generated(),new TotalWork());
    }
    private Optional<M> emit(Lane lane,M move) {
        if(!lane.provider.batch())generated.add(move);
        if(lane.provider.descriptor().sourceKind()==SearchMove.SourceKind.LEARNED)learnedBurst++;
        else if(lane.provider.descriptor().sourceKind()==SearchMove.SourceKind.PRIMITIVE)learnedBurst=0;
        return Optional.of(move);
    }
    private long pullWork() {
        return Math.addExact(workMetrics().totalWorkUnitsV2(),
            nativeObservation?de.regelsuche.retention.RetainedOperation.observedWork():0);
    }
    private Lane selected() {
        Lane first=null;
        for(var lane:lanes) {
            if(lane.finished)continue;
            if(first==null)first=lane;
            if(learnedBurst<2)return first;
            if(lane.provider.descriptor().sourceKind()==SearchMove.SourceKind.PRIMITIVE)return lane;
        }
        return first;
    }
    public int nextStage() { var lane = selected(); return lane == null ? MovePriorityPolicy.Stage.values().length : lane.stage; }
    public TransformationWorkMetrics workMetrics() {
        var work = ordering;
        for (var lane : lanes) if (lane.cursor != null) work = work.plus(lane.cursor.snapshot().work().metrics());
        return work;
    }
    public List<M> generatedMoves() { return List.copyOf(generated); }
    @Override public List<Snapshot> batchCursorReceipts() {
        for(var lane:lanes)if(lane.cursor!=null){
            var receipts=lane.cursor.batchCursorReceipts();
            for(int i=lane.capturedBatchReceipts;i<receipts.size();i++){
                batchReceipts.add(receipts.get(i));de.regelsuche.retention.RetainedOperation.work(1);
            }
            lane.capturedBatchReceipts=receipts.size();
        }
        return List.copyOf(batchReceipts);
    }
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
