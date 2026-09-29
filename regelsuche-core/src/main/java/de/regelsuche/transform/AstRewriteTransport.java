package de.regelsuche.transform;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/** Explicit typed primitive boundary. Construction of a step is not proof of its validity. */
public final class AstRewriteTransport implements RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(engine);}

    public static final String REVISION = "regelsuche.ast-rewrite-transport/v1";
    public static final int MAXIMUM_NODES = 10_000;
    public static final int MAXIMUM_DEPTH = 128;
    public static final int MAXIMUM_TRACE_STEPS = 64;

    /** Structural source and target, with the primitive rule's metadata. No display-text identity. */
    public record Step(Expr source, Expr target, String rule, RewriteKind kind,
            boolean mayIncreaseComplexity, int estimatedCostDelta, boolean equivalencePreservingByConstruction,
            List<String> assumptions, String packId, String license) implements RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(source);v.reference(target);v.reference(rule);v.reference(kind);v.reference(assumptions);v.reference(packId);v.reference(license);}

        public Step {
            requireBounded(source);
            requireBounded(target);
            Objects.requireNonNull(kind, "kind");
            if (rule == null || rule.isBlank() || packId == null || packId.isBlank()
                    || license == null || license.isBlank()) {
                throw new IllegalArgumentException("rule and attribution must be present");
            }
            assumptions = AssumptionSignature.ofExpressions(assumptions).normalizedAssumptions();
        }
    }

    private final PreparedAstRewriteTransformationEngine engine;

    public AstRewriteTransport(List<RewriteRule> rules, int maximumGrowth, int maximumCandidates) {
        this(rules, maximumGrowth, maximumCandidates, false);
    }

    AstRewriteTransport(List<RewriteRule> rules, int maximumGrowth, int maximumCandidates, boolean indexed) {
        if (maximumCandidates < 1) throw new IllegalArgumentException("positive candidate bound required");
        engine = new PreparedAstRewriteTransformationEngine(rules, maximumGrowth, maximumCandidates, indexed);
    }

    /** Generate with actual producer ASTs. Neither source nor result is formatted and reparsed here. */
    public List<Step> generate(Expr source) {
        return engine.transformAst(source);
    }

    /**
     * Regenerate each primitive under this engine's rules and bounds, checking all retained metadata.
     * This proves replay relative to those rules, not the validity of arbitrary user-supplied rules
     * or the truth of their retained side conditions.
     */
    public Expr replay(Expr source, List<Step> steps) {
        requireBounded(source);
        Objects.requireNonNull(steps, "steps");
        if (steps.isEmpty() || steps.size() > MAXIMUM_TRACE_STEPS) {
            throw new IllegalArgumentException("require 1 through 64 primitive steps");
        }
        var retained = List.copyOf(steps);
        Expr current = source;
        for (var step : retained) {
            if (!current.equals(step.source()) || !generate(current).contains(step)) {
                throw new IllegalArgumentException("AST step differs from source-bound primitive regeneration");
            }
            current = step.target();
        }
        return current;
    }

    private record Node(Expr expression, int depth) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(expression);}
    }

    static void requireBounded(Expr root) {
        Objects.requireNonNull(root, "expression");
        var pending = new ArrayDeque<Node>();
        Node[] current = {null};
        try (var retained = RetainedOperation.retainCompleted(2, root, pending, current)) {
            pending.push(new Node(root, 0));
            RetainedOperation.work(1);
            RetainedOperation.checkpoint();
            int nodes = 0;
            while (!pending.isEmpty()) {
                // Move an already-owned Node from the queue into one stable current slot.
                // Dropping the previous Node cannot grow the retained graph; no fresh Frame is needed.
                current[0] = pending.pop();
                RetainedOperation.work(2);
                Node node = current[0];
                RetainedOperation.validation(1);
                if (++nodes > MAXIMUM_NODES || node.depth() > MAXIMUM_DEPTH) {
                    throw new IllegalArgumentException("AST transport structural limit exceeded");
                }
                if (node.expression() instanceof BinaryExpr binary) {
                    pending.push(new Node(binary.right(), node.depth() + 1));
                    pending.push(new Node(binary.left(), node.depth() + 1));
                    RetainedOperation.work(2);
                    RetainedOperation.checkpoint();
                } else if (node.expression() instanceof FunctionExpr function) {
                    if (function.arguments().size() > MAXIMUM_NODES - nodes) {
                        throw new IllegalArgumentException("AST transport argument limit exceeded");
                    }
                    for (var argument : function.arguments()) {
                        pending.push(new Node(argument, node.depth() + 1));
                        RetainedOperation.work(1);
                    }
                    if (!function.arguments().isEmpty()) RetainedOperation.checkpoint();
                }
            }
        }
    }
}
