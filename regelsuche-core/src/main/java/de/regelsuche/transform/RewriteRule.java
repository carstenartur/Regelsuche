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

    /**
     * Whether a matching application preserves equivalence under all conditions
     * returned by {@link #assumptions(Expr)}. Callers must retain or discharge
     * those concrete conditions; callers without an assumption channel must
     * reject applications that introduce any conditions.
     */
    boolean isEquivalencePreservingByConstruction();

    boolean matches(Expr subtree);

    Expr apply(Expr subtree);

    /** Symbolic side conditions introduced by this concrete application. */
    default List<Assumption> assumptions(Expr subtree) {
        return List.of();
    }

    /** Static declaration that this rule can emit a side condition. */
    default boolean mayEmitAssumptions() {
        return false;
    }

    /**
     * Explicit opt-in contract for custom schema-directed preparation. No
     * caller may infer this contract from IDs, classes, examples or benchmarks.
     */
    default Optional<RewriteApplicabilitySchema> explicitApplicabilitySchema() {
        return Optional.empty();
    }

    default RuleDescriptor descriptor() {
        return RuleDescriptor.core(id(), List.of());
    }

    /**
     * Capability implemented by algorithmic/custom rules whose complete source
     * applicability and guard contract is explicitly reviewable.
     */
    interface RewriteApplicabilitySchemaProvider extends RewriteRule {
        RewriteApplicabilitySchema applicabilitySchema();

        @Override
        default Optional<RewriteApplicabilitySchema>
                explicitApplicabilitySchema() {
            return Optional.of(applicabilitySchema());
        }

        /** Builds the standard exact source-pattern contract for this rule. */
        default RewriteApplicabilitySchema exactSource(
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
    }
}
