package de.regelsuche.transform;

/**
 * Explicit opt-in contract for algorithmic rewrite rules that may participate
 * as principals in schema-directed preparation.
 *
 * <p>The provider is implemented by the concrete rule object. Callers must not
 * infer an applicability pattern or guards from a rule ID, implementation
 * class, example, benchmark, or observed execution. The returned schema must
 * retain this exact rule object as its executor.</p>
 */
@FunctionalInterface
public interface RewriteApplicabilitySchemaProvider {
    RewriteApplicabilitySchema applicabilitySchema();
}
