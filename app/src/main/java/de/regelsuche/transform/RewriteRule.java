package de.regelsuche.transform;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import java.util.List;

public interface RewriteRule {
    String id();

    RewriteKind kind();

    boolean mayIncreaseComplexity();

    int estimatedCostDelta();

    boolean isEquivalencePreservingByConstruction();

    boolean matches(Expr subtree);

    Expr apply(Expr subtree);

    /**
     * @return symbolic side conditions the rule introduces when fired on the
     *         given subtree. Default: none (the rule is unconditional). Rules
     *         that introduce/eliminate a divisor or restrict the domain of an
     *         argument should override this to surface the assumption.
     *
     *         <p>Implementations are expected to be cheap and to return the
     *         assumptions <em>specific to the given subtree</em>; if a rule is
     *         instantiated with concrete sub-expressions, the returned
     *         assumption should reference those sub-expressions.</p>
     */
    default List<Assumption> assumptions(Expr subtree) {
        return List.of();
    }
}
