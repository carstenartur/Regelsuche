package de.regelsuche.plugin;

import de.regelsuche.ast.Expr;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;

public interface PatternTransformation extends RewriteRule {
    boolean matches(Expr node, TransformationMatchContext context);

    Expr transform(Expr node, TransformationContext context);

    default String explain(Expr before, Expr after) {
        return id();
    }

    @Override
    default RewriteKind kind() {
        return RewriteKind.NORMALIZE;
    }

    @Override
    default boolean mayIncreaseComplexity() {
        return false;
    }

    @Override
    default int estimatedCostDelta() {
        return 0;
    }

    @Override
    default boolean isEquivalencePreservingByConstruction() {
        return true;
    }

    @Override
    default boolean matches(Expr subtree) {
        return matches(subtree, new TransformationMatchContext(subtree, java.util.Map.of()));
    }

    @Override
    default Expr apply(Expr subtree) {
        return transform(subtree, new TransformationContext(subtree, java.util.Map.of()));
    }
}
