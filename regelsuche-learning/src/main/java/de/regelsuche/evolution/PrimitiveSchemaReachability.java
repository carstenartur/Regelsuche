package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveProvider;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.TransformationWorkMetrics;

/** Conservative whole-task proof over the exact primitive rewrite inventory installed in the solver. */
final class PrimitiveSchemaReachability {
    static final String REVISION = "invariant-operator-absence/v1";
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private final CheckedLearnedSchemaModel model;
    private final long required;
    private final long compilationWork;
    private final long patternVisits;

    private PrimitiveSchemaReachability(CheckedLearnedSchemaModel model, long required, long compilationWork, long patternVisits) {
        this.model = model;
        this.required = required;
        this.compilationWork = compilationWork;
        this.patternVisits = patternVisits;
    }

    static PrimitiveSchemaReachability compile(EvolutionGenome inventory, CheckedLearnedSchemaModel model) {
        if (BinaryOperator.values().length > Long.SIZE) return new PrimitiveSchemaReachability(model, 0, 1, 0);
        var work = new CheckedSchemaSupport.Work();
        long introduced = 0;
        long parseWork = 0;
        for (var gene : inventory.rewrites()) {
            parseWork += gene.sourcePattern().length() + gene.targetPattern().length() + 1L;
            long before = operators(EvolutionGenomeCompiler.parsePattern(gene.sourcePattern()), work);
            long after = operators(EvolutionGenomeCompiler.parsePattern(gene.targetPattern()), work);
            introduced |= after & ~before;
        }
        long common = model.schemas().isEmpty() ? 0 : ~0L;
        for (var schema : model.schemas()) common &= operators(schema.source(), work);
        // A placeholder only copies subtrees from the matched input. A literal operator
        // missing before the step can first appear only in an explicitly introducing RHS.
        return new PrimitiveSchemaReachability(model, common & ~introduced,
            // Copy the primitive list into the guarded session and prepare its fallback and receipt once.
            parseWork + work.units + model.schemas().size() + 2L * inventory.rewrites().size() + 8, work.units);
    }

    private static long operators(PatternExpr pattern, CheckedSchemaSupport.Work work) {
        work.add(1);
        if (pattern instanceof PatternExpr.Operation op)
            return bit(op.operator()) | operators(op.left(), work) | operators(op.right(), work);
        if (pattern instanceof PatternExpr.Function fn) {
            long result = 0;
            for (var argument : fn.arguments()) result |= operators(argument, work);
            return result;
        }
        return 0;
    }

    private static long bit(BinaryOperator operator) { return 1L << operator.ordinal(); }
    long compilationWork() { return compilationWork; }
    long patternVisits() { return patternVisits; }

    /**
     * Even an impossible schema reports an incomplete relation on an unsupported state.
     * Keep this check on one primitive lane, also when a rewrite leaves the checked domain.
     * An exhaustive expansion necessarily opens that lane. A quality stop is nonexhaustive.
     */
    TypedMoveSearch.TypedProvider withDomainCheck(MoveProvider primitive) {
        return new TypedMoveSearch.TypedProvider() {
            @Override public Descriptor descriptor() { return primitive.descriptor(); }
            @Override public Batch candidates(MoveState state, MoveContext context) {
                var batch = primitive.candidates(state, context);
                var work = new CheckedSchemaSupport.Work();
                work.add(1);
                var requiredAssumptions = model.requiredAssumptions();
                if (!requiredAssumptions.isEmpty())
                    work.add(requiredAssumptions.size() + (long) context.initialAssumptions().size() + state.assumptions().size());
                if (requiredAssumptions.isEmpty() || context.carries(requiredAssumptions, state)) {
                    boolean complete = batch.complete();
                    if (state.expression().length() > 262_144) complete = false;
                    else {
                        try {
                            CheckedSchemaSupport.domain(CODEC.decodeExpression(state.expression()), model.bounds(), work);
                        } catch (CheckedSchemaSupport.DomainRejected unsupported) {
                            complete = false;
                        }
                    }
                    var cost = TransformationWorkMetrics.ZERO.withDelegatedMechanicalWork(work.units);
                    return new Batch(batch.moves(), batch.work().plus(cost), complete);
                }
                return new Batch(batch.moves(), batch.work().plus(
                    TransformationWorkMetrics.ZERO.withDelegatedMechanicalWork(work.units)), batch.complete());
            }
        };
    }

    record Decision(boolean primitiveOnly, int nodes, long work) {}

    static final class OperatorScan {
        private long missing;
        final int maximumNodes;
        OperatorScan(long required, int maximumNodes) { this.missing = required; this.maximumNodes = maximumNodes; }
        void observe(BinaryOperator operator, CheckedSchemaSupport.Work work) {
            if (missing != 0) {
                work.add(1);
                missing &= ~bit(operator);
            }
        }
    }

    Decision inspect(Expr source, long allowance) {
        if (required == 0 || allowance < 7 || RetainedOperation.isObserved()) return new Decision(false, 0, 1);
        var work = new CheckedSchemaSupport.Work();
        // Four units create the query scanner, initialize its mask, select the solver and return the decision.
        // One normal visit and at most one operator check per node, plus the final failed bound check.
        work.add(4);
        int limit = (int) Math.min(model.bounds().maximumExpressionNodes(), (allowance - 5) / 2);
        var scan = new OperatorScan(required, limit);
        try {
            int nodes = CheckedSchemaSupport.countedDomain(source, model.bounds(), work, false, scan);
            return new Decision(scan.missing != 0, nodes, work.units);
        } catch (CheckedSchemaSupport.DomainRejected inconclusive) {
            return new Decision(false, 0, work.units);
        }
    }
}
