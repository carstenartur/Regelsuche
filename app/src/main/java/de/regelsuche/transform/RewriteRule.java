package de.regelsuche.transform;

import de.regelsuche.ast.Expr;

public interface RewriteRule {
    String id();

    RewriteKind kind();

    boolean mayIncreaseComplexity();

    int estimatedCostDelta();

    boolean isEquivalencePreservingByConstruction();

    boolean matches(Expr subtree);

    Expr apply(Expr subtree);
}
