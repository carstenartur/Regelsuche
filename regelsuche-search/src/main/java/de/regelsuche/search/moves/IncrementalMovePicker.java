package de.regelsuche.search.moves;

import de.regelsuche.transform.TransformationCursor;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** No scores or sorting: provider inventory order, then each provider's native occurrence order. */
public final class IncrementalMovePicker implements MovePicker, AutoCloseable {
    private static final class Lane {
        final IncrementalMoveProvider provider;
        TransformationCursor cursor;
        boolean checked, rejected;
        Lane(IncrementalMoveProvider provider) { this.provider = provider; }
    }
    private final List<Lane> lanes;
    private final MoveState state;
    private final MoveContext context;
    private final List<SearchMove> generated = new ArrayList<>();
    private int index;
    private boolean closed, workExhausted;

    public IncrementalMovePicker(List<MoveProvider> providers, MoveState state, MoveContext context) {
        lanes = List.copyOf(providers).stream().map(provider -> {
            if (!(provider instanceof IncrementalMoveProvider incremental))
                throw new IllegalArgumentException("native ordering requires incremental providers");
            return new Lane(incremental);
        }).toList();
        this.state = state; this.context = context;
    }

    @Override public Optional<SearchMove> next() { return next(Long.MAX_VALUE); }

    public Optional<SearchMove> next(long allowance) {
        if (allowance < 0) throw new IllegalArgumentException("negative move allowance");
        if (closed || workExhausted) return Optional.empty();
        long before = workMetrics().totalWorkUnitsV2();
        while (index < lanes.size()) {
            if (remaining(before, allowance) == 0) { workExhausted = true; return Optional.empty(); }
            Lane lane = lanes.get(index);
            if (!lane.checked) open(lane);
            if (lane.rejected) { index++; continue; }
            var candidate = lane.cursor.next(remaining(before, allowance));
            if (candidate.isPresent()) {
                var move = SearchMove.from(candidate.orElseThrow(), lane.provider.descriptor(), workMetrics().totalWorkUnits());
                move.provenance().requireSource(state.expression());
                generated.add(move);
                return Optional.of(move);
            }
            if (lane.cursor.snapshot().status() == TransformationCursor.Status.WORK_EXHAUSTED) {
                workExhausted = true; return Optional.empty();
            }
            lane.cursor.close(); index++;
        }
        return Optional.empty();
    }

    private long remaining(long before, long allowance) {
        return Math.max(0, allowance - (workMetrics().totalWorkUnitsV2() - before));
    }
    private void open(Lane lane) {
        lane.checked = true;
        try {
            lane.cursor = lane.provider.openCursor(state, context);
        } catch (IncrementalMoveProvider.AssumptionsNotCarried rejected) {
            lane.rejected = true;
        }
    }
    @Override public TransformationWorkMetrics workMetrics() {
        long checked = lanes.stream().filter(lane -> lane.checked).count();
        long rejected = lanes.stream().filter(lane -> lane.rejected).count();
        var work = new TransformationWorkMetrics(0, 0, 0, 0, 0, checked, rejected, 0, 0, 0, 0, 0, 0, 0);
        for (var lane : lanes) if (lane.cursor != null) work = work.plus(lane.cursor.work().metrics());
        return work;
    }
    @Override public List<SearchMove> generatedMoves() { return List.copyOf(generated); }
    @Override public boolean complete() {
        return index == lanes.size() && !workExhausted
            && lanes.stream().allMatch(lane -> lane.rejected || lane.cursor.snapshot().complete());
    }
    public boolean workExhausted() { return workExhausted; }
    public IncrementalMoveExecution.Expansion receipt() {
        var receipts = new ArrayList<IncrementalMoveExecution.Lane>();
        for (int i = 0; i < lanes.size(); i++) {
            var lane = lanes.get(i);
            receipts.add(new IncrementalMoveExecution.Lane(i, lane.checked, lane.rejected,
                lane.cursor == null ? null : lane.cursor.snapshot()));
        }
        return new IncrementalMoveExecution.Expansion(state, closed, receipts);
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        for (var lane : lanes) if (lane.cursor != null) lane.cursor.close();
    }
}
