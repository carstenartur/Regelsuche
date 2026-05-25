package de.regelsuche.transform;

import de.regelsuche.ast.Expr;
import java.util.HashMap;
import java.util.Map;

public class PatternRewriteRule implements RewriteRule {
    private final String id;
    private final PatternExpr source;
    private final PatternExpr target;
    private final RewriteKind kind;
    private final boolean mayIncreaseComplexity;
    private final int estimatedCostDelta;
    private final boolean equivalencePreservingByConstruction;

    public PatternRewriteRule(String id, PatternExpr source, PatternExpr target) {
        this(id, source, target, RewriteKind.NORMALIZE, false, 0, true);
    }

    public PatternRewriteRule(
        String id,
        PatternExpr source,
        PatternExpr target,
        RewriteKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreservingByConstruction
    ) {
        if (id == null || id.isBlank() || source == null || target == null) {
            throw new IllegalArgumentException("id, source and target are required");
        }
        this.id = id;
        this.source = source;
        this.target = target;
        this.kind = kind;
        this.mayIncreaseComplexity = mayIncreaseComplexity;
        this.estimatedCostDelta = estimatedCostDelta;
        this.equivalencePreservingByConstruction = equivalencePreservingByConstruction;
    }

    @Override
    public String id() {
        return id;
    }

    /**
     * Source (left-hand side) pattern of this rule. Exposed so e-graph
     * adapters in {@code de.regelsuche.egraph} can match the pattern
     * directly against e-nodes/e-classes instead of materialising every
     * concrete representative first.
     */
    public PatternExpr source() {
        return source;
    }

    /**
     * Target (right-hand side) pattern of this rule. Exposed so e-graph
     * adapters can instantiate the rewrite directly inside the e-graph
     * (a-la egg's {@code Applier}) rather than via an AST round-trip.
     */
    public PatternExpr target() {
        return target;
    }

    @Override
    public RewriteKind kind() {
        return kind;
    }

    @Override
    public boolean mayIncreaseComplexity() {
        return mayIncreaseComplexity;
    }

    @Override
    public int estimatedCostDelta() {
        return estimatedCostDelta;
    }

    @Override
    public boolean isEquivalencePreservingByConstruction() {
        return equivalencePreservingByConstruction;
    }

    @Override
    public boolean matches(Expr subtree) {
        return source.match(subtree, new HashMap<>());
    }

    @Override
    public Expr apply(Expr subtree) {
        Map<String, Expr> bindings = new HashMap<>();
        if (!source.match(subtree, bindings)) {
            throw new IllegalArgumentException("Rule does not match subtree");
        }
        return target.instantiate(bindings);
    }
}
