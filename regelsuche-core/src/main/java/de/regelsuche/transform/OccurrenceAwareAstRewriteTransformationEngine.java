package de.regelsuche.transform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * AST rewrite engine whose application keys distinguish equal source subtrees by
 * the resulting whole-expression transition.
 *
 * <p>Two equal occurrences can share the same subtree hash. Rewriting the left or
 * right occurrence generally produces different root expressions, so a compact
 * syntax-sensitive digest of the result allows both local applications while still
 * collapsing truly duplicate transitions. The class extends
 * {@link AstRewriteTransformationEngine} so rule inventories remain visible to
 * audit and leakage inspection.</p>
 */
public final class OccurrenceAwareAstRewriteTransformationEngine
        extends AstRewriteTransformationEngine {

    public OccurrenceAwareAstRewriteTransformationEngine(List<RewriteRule> rules) {
        super(rules);
    }

    public OccurrenceAwareAstRewriteTransformationEngine(
        List<RewriteRule> rules,
        int maxAstSizeIncreasePerStep,
        int maxCandidatesPerState
    ) {
        super(rules, maxAstSizeIncreasePerStep, maxCandidatesPerState);
    }

    @Override
    public List<Transformation> transform(String expression) {
        return super.transform(expression).stream()
            .map(OccurrenceAwareAstRewriteTransformationEngine::withOccurrenceTransition)
            .toList();
    }

    private static Transformation withOccurrenceTransition(Transformation transformation) {
        String key = transformation.applicationKey()
            + "->syntax-v1:" + syntaxDigest(transformation.transformedExpression());
        return new Transformation(
            transformation.rule(),
            transformation.transformedExpression(),
            transformation.kind(),
            transformation.mayIncreaseComplexity(),
            transformation.estimatedCostDelta(),
            transformation.equivalencePreservingByConstruction(),
            key,
            transformation.assumptions(),
            transformation.packId(),
            transformation.license());
    }

    private static String syntaxDigest(String expression) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(expression.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
