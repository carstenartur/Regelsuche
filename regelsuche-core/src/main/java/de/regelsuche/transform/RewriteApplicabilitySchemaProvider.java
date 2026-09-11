package de.regelsuche.transform;

import java.util.List;

/**
 * Explicit opt-in contract for algorithmic rewrite rules that may participate
 * as principals in schema-directed preparation.
 *
 * <p>The provider is the concrete rule object. Callers must not infer an
 * applicability pattern or guards from an ID, implementation class, example,
 * benchmark, or observed execution.</p>
 */
public interface RewriteApplicabilitySchemaProvider extends RewriteRule {
    RewriteApplicabilitySchema applicabilitySchema();

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
