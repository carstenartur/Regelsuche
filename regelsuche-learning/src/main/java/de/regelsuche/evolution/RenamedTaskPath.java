package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveProvider;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.TransformationProvenance;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
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

    private record Step(Expr target, SearchMove template) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(target);
            visitor.reference(template);
        }
    }

    private final List<Step> steps;

    private record Branch(Expr left, BinaryOperator operator, Expr right) {
        @Override public boolean equals(Object other) {
            return other instanceof Branch branch && left == branch.left && operator == branch.operator && right == branch.right;
        }

        @Override public int hashCode() {
            return 31 * (31 * System.identityHashCode(left) + operator.hashCode()) + System.identityHashCode(right);
        }
    }

    private RenamedTaskPath(List<Step> steps) { this.steps = List.copyOf(steps); }

    static RenamedTaskPath retain(Shape shape, List<TypedMoveSearch.WitnessStep> witness, CheckedSchemaSupport.Work work) {
        work.add(1);
        if (witness.isEmpty() || witness.size() > MAXIMUM_STEPS) return null;
        var steps = new ArrayList<Step>();
        var branches = new HashMap<Branch, BinaryExpr>();
        var numbers = new HashMap<NumberExpr, NumberExpr>();
        for (var edge : witness) {
            work.add(1);
            var move = edge.move();
            if (move.sourceKind() != SearchMove.SourceKind.PRIMITIVE || !move.assumptions().isEmpty()
                    || !move.capabilityDelta().isEmpty() || !move.transformation().rule().equals(move.ruleId())
                    || !move.transformation().primitiveRuleIds().equals(List.of(move.ruleId()))
                    || !(move.provenance() instanceof TransformationProvenance.PrimitiveRewriteSequence)) return null;
            Expr target = rewrite(edge.target().expression(), shape.slots()::get, work);
            if (target == null) return null; // An introduced symbol has no binding in the new task.
            steps.add(new Step(intern(target, branches, numbers, work), move));
        }
        return new RenamedTaskPath(steps);
    }

    TypedMoveSearch.TypedProvider bind(Shape shape, CheckedSchemaSupport.Work work) {
        var names = new HashMap<VariableExpr, VariableExpr>();
        shape.slots().forEach((variable, slot) -> names.put(slot, variable));
        work.add(2L + names.size() + steps.size());
        return new TypedMoveSearch.TypedProvider() {
            private final String[] endpoints = new String[steps.size()];
            private final Map<Expr, Expr> instantiated = new IdentityHashMap<>();
            @Override public MoveProvider.Descriptor descriptor() { return DESCRIPTOR; }

            @Override public MoveProvider.Batch candidates(MoveState state, MoveContext context) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("renamed path replay interrupted");
                var local = new CheckedSchemaSupport.Work();
                local.add(1);
                int index = state.searchDepth();
                if (index >= steps.size() || !state.assumptions().isEmpty() || !context.initialAssumptions().isEmpty())
                    return batch(List.of(), local);
                local.add(1);
                String expected = index == 0 ? CODEC.encodeExpression(shape.concrete()) : endpoints[index - 1];
                if (!state.expression().equals(expected)) return batch(List.of(), local);
                var step = steps.get(index);
                if (endpoints[index] == null) {
                    Expr target = instantiate(step.target(), names, instantiated, local);
                    endpoints[index] = CODEC.encodeExpression(target);
                    local.add(1);
                }
                local.add(1); // Construct a fresh source-bound proposal for the actual receiving state.
                return batch(List.of(TypedMoveSearch.rebindPrimitiveProposal(step.template(), state, endpoints[index], local.units)), local);
            }
        };
    }

    private static MoveProvider.Batch batch(List<SearchMove> moves, CheckedSchemaSupport.Work work) {
        return new MoveProvider.Batch(moves, TransformationWorkMetrics.ZERO.withDelegatedMechanicalWork(work.units), false);
    }

    private static Expr intern(Expr term, Map<Branch, BinaryExpr> branches, Map<NumberExpr, NumberExpr> numbers,
            CheckedSchemaSupport.Work work) {
        work.add(1);
        if (term instanceof NumberExpr number) return numbers.computeIfAbsent(number, value -> value);
        if (!(term instanceof BinaryExpr binary)) return term;
        var branch = new Branch(intern(binary.left(), branches, numbers, work), binary.operator(),
            intern(binary.right(), branches, numbers, work));
        work.add(1);
        return branches.computeIfAbsent(branch, value -> new BinaryExpr(value.left(), value.operator(), value.right()));
    }

    private static Expr instantiate(Expr term, Map<VariableExpr, VariableExpr> names, Map<Expr, Expr> instantiated,
            CheckedSchemaSupport.Work work) {
        work.add(1);
        if (term instanceof VariableExpr variable) return names.get(variable);
        if (!(term instanceof BinaryExpr binary)) return term;
        Expr previous = instantiated.get(term);
        if (previous != null) return previous;
        var target = new BinaryExpr(instantiate(binary.left(), names, instantiated, work), binary.operator(),
            instantiate(binary.right(), names, instantiated, work));
        instantiated.put(term, target);
        work.add(1);
        return target;
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
