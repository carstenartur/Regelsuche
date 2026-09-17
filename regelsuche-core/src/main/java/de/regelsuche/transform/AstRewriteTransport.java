package de.regelsuche.transform;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/** Explicit typed primitive boundary. Construction of a step is not proof of its validity. */
public final class AstRewriteTransport {
    public static final String REVISION = "regelsuche.ast-rewrite-transport/v1";
    public static final int MAXIMUM_NODES = 10_000;
    public static final int MAXIMUM_DEPTH = 128;
    public static final int MAXIMUM_TRACE_STEPS = 64;

    /** Structural source and target, with the primitive rule's metadata. No display-text identity. */
    public record Step(Expr source, Expr target, String rule, RewriteKind kind,
            boolean mayIncreaseComplexity, int estimatedCostDelta, boolean equivalencePreservingByConstruction,
            List<String> assumptions, String packId, String license) {
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
        if (maximumCandidates < 1) throw new IllegalArgumentException("positive candidate bound required");
        engine = new PreparedAstRewriteTransformationEngine(rules, maximumGrowth, maximumCandidates);
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

    private record Node(Expr expression, int depth) {}

    static void requireBounded(Expr root) {
        Objects.requireNonNull(root, "expression");
        var pending = new ArrayDeque<Node>();
        pending.push(new Node(root, 0));
        int nodes = 0;
        while (!pending.isEmpty()) {
            var node = pending.pop();
            if (++nodes > MAXIMUM_NODES || node.depth() > MAXIMUM_DEPTH) {
                throw new IllegalArgumentException("AST transport structural limit exceeded");
            }
            if (node.expression() instanceof BinaryExpr binary) {
                pending.push(new Node(binary.right(), node.depth() + 1));
                pending.push(new Node(binary.left(), node.depth() + 1));
            } else if (node.expression() instanceof FunctionExpr function) {
                if (function.arguments().size() > MAXIMUM_NODES - nodes) {
                    throw new IllegalArgumentException("AST transport argument limit exceeded");
                }
                for (var argument : function.arguments()) pending.push(new Node(argument, node.depth() + 1));
            }
        }
    }
}
