package de.regelsuche.evolution;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Retained proposals only: the receiving generation must verify every edge again. */
final class RetainedTaskPath implements TypedMoveSearch.TypedProvider, RetainedGraph.View {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final Descriptor DESCRIPTOR = new Descriptor("retained-task-path/v1", "retained-task-path",
        SearchMove.SourceKind.PREPARATION, SearchMove.ProofStrength.REPLAYABLE, List.of(),
        SearchMove.ValueEvidence.UNKNOWN, "source-bound-prior-selected-path");
    private final Map<MoveState, SearchMove> proposals;

    RetainedTaskPath(List<TypedMoveSearch.WitnessStep> witness) {
        var selected = new LinkedHashMap<MoveState, SearchMove>();
        for (var step : witness) {
            var state = step.source();
            var source = new MoveState(CODEC.encodeExpression(state.expression()), state.searchDepth(), state.primitiveDepth(),
                state.previousRule(), state.assumptions(), state.capabilities(), state.complexityDebt());
            if (selected.put(source, step.move()) != null) throw new IllegalArgumentException("duplicate selected path state");
        }
        proposals = Map.copyOf(selected);
    }

    long constructionWork() { return Math.addExact(1, 2L * proposals.size()); }
    @Override public Descriptor descriptor() { return DESCRIPTOR; }

    @Override public Batch candidates(MoveState state, MoveContext context) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("query replay interrupted");
        var move = proposals.get(state);
        var moves = move == null ? List.<SearchMove>of() : List.of(move);
        // Retain producer metadata. This receipt pays the current lookup, while Batch
        // also retains the actual mathematical application work of the proposal.
        var work = TransformationWorkMetrics.ZERO.withDelegatedMechanicalWork(1L + moves.size());
        return new Batch(moves, work, false);
    }

    @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(proposals); }
}
