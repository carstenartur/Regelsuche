package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveProvider;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/** A selected primitive path with consistently renamed, unscoped variables. */
final class RenamedTaskPath implements RetainedGraph.View {
    private static final int MAXIMUM_NODES = 256;
    private static final int MAXIMUM_DEPTH = 64;
    private static final int MAXIMUM_STEPS = 32;
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final MoveProvider.Descriptor DESCRIPTOR = new MoveProvider.Descriptor("renamed-task-path/v1",
        "renamed-task-path", SearchMove.SourceKind.PREPARATION, SearchMove.ProofStrength.REPLAYABLE,
        List.of(), SearchMove.ValueEvidence.UNKNOWN, "rebound-prior-primitive-path");

    record Shape(Expr source, Expr concrete, Map<VariableExpr, VariableExpr> slots) {
        static Shape of(Expr source, CheckedSchemaSupport.Work work) {
            var slots = new HashMap<VariableExpr, VariableExpr>();
            Expr numbered = rewrite(source, variable -> slots.computeIfAbsent(variable,
                ignored -> new VariableExpr("slot" + slots.size())), work);
            return numbered == null || slots.isEmpty() ? null : new Shape(numbered, source, slots);
        }
    }

    private record Step(TreePosition position, Expr replacement, TypedMoveSearch.PrimitiveProposal proposal) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(position); visitor.reference(replacement); visitor.reference(proposal);
        }
    }

    private final List<Step> steps;

    private RenamedTaskPath(List<Step> steps) { this.steps = List.copyOf(steps); }

    static RenamedTaskPath retain(Shape shape, List<TypedMoveSearch.WitnessStep> witness, CheckedSchemaSupport.Work work) {
        work.add(1);
        if (witness.isEmpty() || witness.size() > MAXIMUM_STEPS) return null;
        var steps = new ArrayList<Step>();
        for (var edge : witness) {
            work.add(1);
            var proposal = TypedMoveSearch.PrimitiveProposal.from(edge.move());
            if (proposal.isEmpty()) return null;
            var position = new ArrayList<Integer>();
            Expr replaced = replacement(edge.source().expression(), edge.target().expression(), position, work);
            Expr numbered = rewrite(replaced, shape.slots()::get, work, work.units, position.size());
            if (numbered == null) return null; // An introduced symbol has no binding in the new task.
            steps.add(new Step(new TreePosition(position, ""), numbered, proposal.orElseThrow()));
        }
        return new RenamedTaskPath(steps);
    }

    TypedMoveSearch.TypedProvider bind(Shape shape, CheckedSchemaSupport.Work work) {
        var names = new HashMap<VariableExpr, VariableExpr>();
        shape.slots().forEach((variable, slot) -> names.put(slot, variable));
        work.add(2L + names.size());
        return new TypedMoveSearch.TypedProvider() {
            private Expr source = shape.concrete();
            private String expected;
            private int nextStep;
            @Override public MoveProvider.Descriptor descriptor() { return DESCRIPTOR; }

            @Override public MoveProvider.Batch candidates(MoveState state, MoveContext context) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("renamed path replay interrupted");
                var local = new CheckedSchemaSupport.Work();
                local.add(1);
                int index = state.searchDepth();
                if (index != nextStep || index >= steps.size() || !state.assumptions().isEmpty() || !context.initialAssumptions().isEmpty())
                    return batch(List.of(), local);
                local.add(1);
                if (expected == null) expected = CODEC.encodeExpression(source);
                if (!state.expression().equals(expected)) return batch(List.of(), local);
                var step = steps.get(index);
                Expr replacement = rewrite(step.replacement(), names::get, local);
                local.add(2L + 3L * step.position().path().size());
                var target = step.position().replaceAt(source, replacement).rewrittenRoot();
                if (target.isEmpty()) return batch(List.of(), local);
                source = target.orElseThrow();
                expected = CODEC.encodeExpression(source);
                nextStep++;
                local.add(1); // Construct a fresh source-bound proposal for the actual receiving state.
                return batch(List.of(step.proposal().bind(state, expected, local.units)), local);
            }
        };
    }

    private static MoveProvider.Batch batch(List<SearchMove> moves, CheckedSchemaSupport.Work work) {
        return new MoveProvider.Batch(moves, TransformationWorkMetrics.ZERO.withDelegatedMechanicalWork(work.units), false);
    }

    private static Expr replacement(Expr before, Expr after, List<Integer> path, CheckedSchemaSupport.Work work) {
        work.add(1);
        if (path.size() >= MAXIMUM_DEPTH || !(before instanceof BinaryExpr left) || !(after instanceof BinaryExpr right)
                || left.operator() != right.operator()) return after;
        if (same(left.right(), right.right(), work)) {
            path.add(0);
            return replacement(left.left(), right.left(), path, work);
        }
        if (same(left.left(), right.left(), work)) {
            path.add(1);
            return replacement(left.right(), right.right(), path, work);
        }
        return after;
    }

    private static boolean same(Expr left, Expr right, CheckedSchemaSupport.Work work) {
        work.add(1);
        if (left == right) return true;
        if (left instanceof BinaryExpr a && right instanceof BinaryExpr b) {
            return a.operator() == b.operator() && same(a.left(), b.left(), work) && same(a.right(), b.right(), work);
        }
        return left.equals(right);
    }

    private static Expr rewrite(Expr source, Function<VariableExpr, VariableExpr> rename, CheckedSchemaSupport.Work work) {
        return rewrite(source, rename, work, work.units, 0);
    }

    private static Expr rewrite(Expr source, Function<VariableExpr, VariableExpr> rename,
            CheckedSchemaSupport.Work work, long start, int depth) {
        work.add(1);
        if (work.units - start > MAXIMUM_NODES || depth > MAXIMUM_DEPTH) return null;
        return switch (source) {
            case NumberExpr number -> number;
            case VariableExpr variable -> variable.symbol().isEmpty() ? rename.apply(variable) : null;
            case BinaryExpr binary -> {
                Expr left = rewrite(binary.left(), rename, work, start, depth + 1);
                Expr right = left == null ? null : rewrite(binary.right(), rename, work, start, depth + 1);
                yield right == null ? null : new BinaryExpr(left, binary.operator(), right);
            }
            default -> null;
        };
    }

    @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(steps); }
}
