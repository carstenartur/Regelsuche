package de.regelsuche.transform;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import de.regelsuche.knowledge.RuleDescriptor;
import java.util.List;
import java.util.Optional;

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
     *         given subtree. Default: none (the rule is unconditional).
     */
    default List<Assumption> assumptions(Expr subtree) {
        return List.of();
    }

    /** Static declaration that this rule can emit a side condition. */
    default boolean mayEmitAssumptions() {
        return false;
    }

    /**
     * Explicit opt-in contract for algorithmic/custom schema-directed
     * preparation. No caller may infer this contract from rule metadata.
     */
    default Optional<RewriteApplicabilitySchema> explicitApplicabilitySchema() {
        return Optional.empty();
    }

    /**
     * Builds the standard exact source-pattern contract for this exact rule
     * object. Intended for {@link #explicitApplicabilitySchema()} overrides.
     */
    default RewriteApplicabilitySchema exactApplicabilitySchema(
        PatternExpr pattern,
        RequiredAssumptionTemplate... requiredAssumptions
    ) {
        return new RewriteApplicabilitySchema(
            "algorithmic-source/v1:" + id(),
            this,
            pattern,
            RecognitionProfile.exact(),
            List.of(requiredAssumptions));
    }

    default RuleDescriptor descriptor() {
        return RuleDescriptor.core(id(), List.of());
    }
}
