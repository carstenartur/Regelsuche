package de.regelsuche.transform;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import de.regelsuche.knowledge.RuleDescriptor;
import java.util.List;
import java.util.Objects;

/**
 * Adapts an implementation-defined rewrite rule to an explicit declarative
 * applicability schema without replacing its concrete execution semantics.
 *
 * <p>The schema is used only for bounded matching, residual analysis and
 * preparation planning. A candidate is authoritative only when the wrapped
 * rule still matches and applies to the exact retained AST. This prevents a
 * descriptive pattern from silently becoming a second implementation of an
 * algorithmic rule.</p>
 */
public final class SchemaBackedRewriteRule extends PatternRewriteRule {
    private static final PatternExpr RESULT_PLACEHOLDER =
        PatternExpr.var("SCHEMA_BACKED_RESULT");

    private final String schemaId;
    private final RewriteRule delegate;

    public SchemaBackedRewriteRule(
        String schemaId,
        RewriteRule delegate,
        PatternExpr applicabilityPattern,
        RecognitionProfile recognitionProfile
    ) {
        super(
            requireDelegate(delegate).id(),
            Objects.requireNonNull(
                applicabilityPattern, "applicabilityPattern"),
            RESULT_PLACEHOLDER,
            delegate.kind(),
            delegate.mayIncreaseComplexity(),
            delegate.estimatedCostDelta(),
            delegate.isEquivalencePreservingByConstruction(),
            schemaDescriptor(requireSchemaId(schemaId), delegate),
            recognitionProfile == null
                ? RecognitionProfile.exact()
                : recognitionProfile);
        if (delegate instanceof SchemaBackedRewriteRule) {
            throw new IllegalArgumentException(
                "schema-backed rules must not be wrapped again");
        }
        this.schemaId = schemaId.trim();
        this.delegate = delegate;
    }

    public SchemaBackedRewriteRule(
        String schemaId,
        RewriteRule delegate,
        PatternExpr applicabilityPattern
    ) {
        this(
            schemaId,
            delegate,
            applicabilityPattern,
            RecognitionProfile.exact());
    }

    public String schemaId() {
        return schemaId;
    }

    public String delegateClassName() {
        return delegate.getClass().getName();
    }

    @Override
    public boolean matches(Expr subtree) {
        return delegate.matches(subtree);
    }

    @Override
    public Expr apply(Expr subtree) {
        return delegate.apply(subtree);
    }

    @Override
    public List<Assumption> assumptions(Expr subtree) {
        return delegate.assumptions(subtree);
    }

    @Override
    public boolean mayEmitAssumptions() {
        return delegate.mayEmitAssumptions();
    }

    private static RewriteRule requireDelegate(RewriteRule delegate) {
        return Objects.requireNonNull(delegate, "delegate");
    }

    private static String requireSchemaId(String schemaId) {
        if (schemaId == null || schemaId.isBlank()) {
            throw new IllegalArgumentException(
                "schemaId must not be blank");
        }
        return schemaId.trim();
    }

    private static RuleDescriptor schemaDescriptor(
        String schemaId,
        RewriteRule delegate
    ) {
        RuleDescriptor source = Objects.requireNonNull(
            delegate.descriptor(), "delegate descriptor");
        return new RuleDescriptor(
            source.ruleId(),
            source.packId(),
            source.originProject(),
            source.license(),
            source.sourceVersion(),
            source.sourceReference()
                + "; applicabilitySchema=" + schemaId
                + "; delegateClass=" + delegate.getClass().getName(),
            source.derivationType(),
            source.status(),
            source.riskLevel(),
            source.categories(),
            source.searchEffects(),
            source.validationExamples(),
            source.counterExamples());
    }
}
