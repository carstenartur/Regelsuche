package de.regelsuche.transform;

import java.util.List;
import java.util.Objects;

/**
 * Central selector for reference and prepared AST rewrite execution.
 *
 * <p>The prepared backend is the production selection for repeated search and
 * rewrite-program evaluation after the matched-work, end-to-end and allocation
 * measurements from issue #530. The reference backend remains directly
 * selectable as the executable semantic oracle.</p>
 *
 * <p>The selector performs no environment-sensitive auto-detection. Callers
 * that persist experiment or benchmark identities must bind the selected
 * {@link Backend} explicitly.</p>
 */
public final class AstRewriteTransformationEngines {
    public enum Backend {
        REFERENCE,
        PREPARED
    }

    private AstRewriteTransformationEngines() {
    }

    public static Backend productionBackend() {
        return Backend.PREPARED;
    }

    public static TransformationEngine production() {
        return create(productionBackend());
    }

    public static TransformationEngine production(List<RewriteRule> rules) {
        return create(productionBackend(), rules);
    }

    public static TransformationEngine production(
        List<RewriteRule> rules,
        int maxAstSizeIncreasePerStep,
        int maxCandidatesPerState
    ) {
        return create(
            productionBackend(),
            rules,
            maxAstSizeIncreasePerStep,
            maxCandidatesPerState
        );
    }

    public static TransformationEngine reference() {
        return create(Backend.REFERENCE);
    }

    public static TransformationEngine reference(List<RewriteRule> rules) {
        return create(Backend.REFERENCE, rules);
    }

    public static TransformationEngine reference(
        List<RewriteRule> rules,
        int maxAstSizeIncreasePerStep,
        int maxCandidatesPerState
    ) {
        return create(
            Backend.REFERENCE,
            rules,
            maxAstSizeIncreasePerStep,
            maxCandidatesPerState
        );
    }

    public static TransformationEngine create(Backend backend) {
        return switch (Objects.requireNonNull(backend, "backend")) {
            case REFERENCE -> new AstRewriteTransformationEngine();
            case PREPARED -> new PreparedAstRewriteTransformationEngine();
        };
    }

    public static TransformationEngine create(
        Backend backend,
        List<RewriteRule> rules
    ) {
        Objects.requireNonNull(rules, "rules");
        return switch (Objects.requireNonNull(backend, "backend")) {
            case REFERENCE -> new AstRewriteTransformationEngine(rules);
            case PREPARED -> new PreparedAstRewriteTransformationEngine(rules);
        };
    }

    public static TransformationEngine create(
        Backend backend,
        List<RewriteRule> rules,
        int maxAstSizeIncreasePerStep,
        int maxCandidatesPerState
    ) {
        Objects.requireNonNull(rules, "rules");
        return switch (Objects.requireNonNull(backend, "backend")) {
            case REFERENCE -> new AstRewriteTransformationEngine(
                rules,
                maxAstSizeIncreasePerStep,
                maxCandidatesPerState
            );
            case PREPARED -> new PreparedAstRewriteTransformationEngine(
                rules,
                maxAstSizeIncreasePerStep,
                maxCandidatesPerState
            );
        };
    }
}
