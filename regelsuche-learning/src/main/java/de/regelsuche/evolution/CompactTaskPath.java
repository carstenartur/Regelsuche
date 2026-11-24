package de.regelsuche.evolution;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveProvider;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Source-bound primitive proposals for exact repeats, without retained proof receipts. */
final class CompactTaskPath implements TypedMoveSearch.TypedProvider, RetainedGraph.View {
    private static final Descriptor DESCRIPTOR = new Descriptor("compact-task-path/v1", "compact-task-path",
        SearchMove.SourceKind.PREPARATION, SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN,
        "source-bound-prior-primitive-path");

    private record Step(String target, TypedMoveSearch.PrimitiveProposal proposal) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(target); visitor.reference(proposal);
        }
    }

    private final String source;
    private final List<Step> steps;
    private final long characters;

    private CompactTaskPath(String source, List<Step> steps, long characters) {
        this.source = source;
        this.steps = List.copyOf(steps);
        this.characters = characters;
    }

    static CompactTaskPath retain(List<MoveSearch.WitnessStep> witness, CheckedSchemaSupport.Work work) {
        work.add(1);
        if (witness.isEmpty() || witness.size() > 32) return null;
        String initial = witness.getFirst().source().expression();
        String current = initial;
        // The initial document bounds the exact key AST as well as its own retained string.
        long characters = 2L * initial.length();
        var steps = new ArrayList<Step>();
        for (var edge : witness) {
            work.add(1);
            var proposal = TypedMoveSearch.PrimitiveProposal.from(edge.move());
            if (proposal.isEmpty() || !edge.source().assumptions().isEmpty() || !current.equals(edge.source().expression())) return null;
            current = edge.target().expression();
            var recipe = proposal.orElseThrow();
            characters = Math.addExact(characters, (long) current.length() + recipe.rule().length() + recipe.family().length()
                + recipe.pack().length() + recipe.license().length());
            work.add(5);
            steps.add(new Step(current, recipe));
        }
        return new CompactTaskPath(initial, steps, characters);
    }

    long characters() { return characters; }

    @Override public Descriptor descriptor() { return DESCRIPTOR; }

    @Override public MoveProvider.Batch candidates(MoveState state, MoveContext context) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("compact path replay interrupted");
        int depth = state.searchDepth();
        if (depth >= steps.size() || !state.assumptions().isEmpty() || !context.initialAssumptions().isEmpty()
                || !state.expression().equals(depth == 0 ? source : steps.get(depth - 1).target())) {
            return new Batch(List.of(), TransformationWorkMetrics.ZERO.withDelegatedMechanicalWork(1), false);
        }
        var step = steps.get(depth);
        return new Batch(List.of(step.proposal().bind(state, step.target(), 2)),
            TransformationWorkMetrics.ZERO.withDelegatedMechanicalWork(2), false);
    }

    @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
        visitor.reference(source); visitor.reference(steps);
    }
}
