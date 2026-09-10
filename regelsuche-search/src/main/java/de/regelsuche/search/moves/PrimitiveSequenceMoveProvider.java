package de.regelsuche.search.moves;

import de.regelsuche.search.program.RewriteCandidate;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.List;

/** Finite primitive sequence relation, including all intermediate work and every bound proof path. */
public record PrimitiveSequenceMoveProvider(Descriptor descriptor, List<MoveProvider> sources) implements MoveProvider {
    public PrimitiveSequenceMoveProvider {
        sources = List.copyOf(sources);
        if (sources.isEmpty() || sources.size() > 8 || sources.stream().anyMatch(source -> source.descriptor().sourceKind() != SearchMove.SourceKind.PRIMITIVE))
            throw new IllegalArgumentException("sequence requires one to eight primitive providers");
    }
    @Override public Batch candidates(MoveState state, MoveContext context) {
        List<RewriteCandidate> current = List.of();
        var work = TransformationWorkMetrics.ZERO; boolean complete = true;
        long compositions = 0;
        for (int index = 0; index < sources.size(); index++) {
            var next = new ArrayList<RewriteCandidate>();
            int prefixes = index == 0 ? 1 : current.size();
            for (int p = 0; p < prefixes; p++) {
                String input = index == 0 ? state.expression() : current.get(p).outputExpression();
                var position = new MoveState(input, state.searchDepth(), state.primitiveDepth() + index, state.previousRule(),
                    state.assumptions(), state.capabilities(), state.complexityDebt());
                var batch = sources.get(index).candidates(position, context);
                work = work.plus(batch.work()); complete &= batch.complete();
                for (var move : batch.moves()) {
                    if (move.transformation().primitiveStepCount() != 1 || move.transformation().exactTheoryStepCount() != 0)
                        throw new IllegalArgumentException("nonprimitive source in primitive sequence");
                    var suffix = new RewriteCandidate(sources.get(index).descriptor().id(), input,
                        move.transformation().transformedExpression(), List.of(move.transformation()));
                    next.add(index == 0 ? suffix : current.get(p).append(suffix, descriptor.id()));
                    if (index != 0) compositions++;
                }
            }
            current = List.copyOf(next);
            if (current.isEmpty()) break;
        }
        work = work.plus(new TransformationWorkMetrics(1, 0, 0, 0, compositions, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        long measured = work.totalWorkUnits();
        return new Batch(current.stream().map(candidate -> SearchMove.from(candidate.withOriginNodeId(descriptor.id()).toTransformation(),
            descriptor, measured)).toList(), work, complete);
    }
}
