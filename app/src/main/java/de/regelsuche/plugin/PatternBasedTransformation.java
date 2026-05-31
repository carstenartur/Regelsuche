package de.regelsuche.plugin;

import de.regelsuche.ast.Expr;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.RewriteKind;
import java.util.HashMap;
import java.util.Map;

public final class PatternBasedTransformation implements PatternTransformation {
    private final String id;
    private final PatternExpr source;
    private final PatternExpr target;
    private final RewriteKind kind;
    private final boolean mayIncreaseComplexity;
    private final int estimatedCostDelta;
    private final boolean equivalencePreservingByConstruction;
    private final String explanation;

    public PatternBasedTransformation(
        String id,
        PatternExpr source,
        PatternExpr target,
        RewriteKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreservingByConstruction,
        String explanation
    ) {
        this.id = id;
        this.source = source;
        this.target = target;
        this.kind = kind;
        this.mayIncreaseComplexity = mayIncreaseComplexity;
        this.estimatedCostDelta = estimatedCostDelta;
        this.equivalencePreservingByConstruction = equivalencePreservingByConstruction;
        this.explanation = explanation == null || explanation.isBlank() ? id : explanation;
    }

    @Override
    public String id() {
        return id;
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
    public boolean matches(Expr node, TransformationMatchContext context) {
        return source.match(node, new HashMap<>());
    }

    @Override
    public Expr transform(Expr node, TransformationContext context) {
        Map<String, Expr> bindings = new HashMap<>();
        if (!source.match(node, bindings)) {
            throw new IllegalArgumentException("Transformation does not match subtree");
        }
        return target.instantiate(bindings);
    }

    @Override
    public String explain(Expr before, Expr after) {
        return explanation;
    }
}
