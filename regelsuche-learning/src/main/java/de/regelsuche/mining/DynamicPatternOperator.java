package de.regelsuche.mining;

import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.HypothesisOperator;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A quarantined, dynamically compiled hypothesis operator that executes an
 * AST rewrite rule specified entirely by a left-hand pattern and a right-hand
 * template, without requiring a hand-written Java operator class.
 *
 * <p>Safety invariants enforced at execution time:
 * <ul>
 *   <li>Identity rewrites (output == input) are suppressed.</li>
 *   <li>Structural cycle guards prevent the operator from reproducing itself:
 *       the rule ID is a hypothesis-specific prefix, not a global rule name.</li>
 *   <li>Operators in CANDIDATE state are not eligible for global activation; only
 *       VALIDATED operators may be promoted by {@link DynamicCandidateRegistry}.</li>
 *   <li>Every emitted transformation carries the hypothesis revision in its
 *       {@code applicationKey}, satisfying edge provenance requirements.</li>
 * </ul>
 * </p>
 *
 * <p>The operator is deterministic: given the same input expression it always
 * produces the same candidate set.</p>
 */
public final class DynamicPatternOperator implements HypothesisOperator {

    /** Prefix used to distinguish dynamically compiled operator IDs from static ones. */
    public static final String RULE_ID_PREFIX = "dynamic_hypothesis_";

    private final String ruleId;
    private final String hypothesisId;
    private final String hypothesisRevision;
    private final String provenanceHash;
    private final String leftPatternText;
    private final String rightPatternText;
    private final RulePatternNode leftPattern;
    private final RulePatternNode rightPattern;
    private final int maxCandidates;

    private final RulePatternMatcher matcher = new RulePatternMatcher();
    private final RulePatternInstantiator instantiator = new RulePatternInstantiator();
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    DynamicPatternOperator(
        String ruleId,
        String hypothesisId,
        String hypothesisRevision,
        String provenanceHash,
        String leftPatternText,
        String rightPatternText,
        RulePatternNode leftPattern,
        RulePatternNode rightPattern,
        int maxCandidates
    ) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must not be blank");
        }
        if (hypothesisId == null || hypothesisId.isBlank()) {
            throw new IllegalArgumentException("hypothesisId must not be blank");
        }
        if (leftPattern == null || rightPattern == null) {
            throw new IllegalArgumentException("patterns must not be null");
        }
        this.ruleId = ruleId;
        this.hypothesisId = hypothesisId;
        this.hypothesisRevision = hypothesisRevision == null ? "" : hypothesisRevision;
        this.provenanceHash = provenanceHash == null ? "" : provenanceHash;
        this.leftPatternText = leftPatternText == null ? "" : leftPatternText;
        this.rightPatternText = rightPatternText == null ? "" : rightPatternText;
        this.leftPattern = leftPattern;
        this.rightPattern = rightPattern;
        this.maxCandidates = maxCandidates < 1 ? 1 : maxCandidates;
    }

    /** The unique rule ID for this dynamic operator (includes hypothesis ID). */
    public String ruleId() {
        return ruleId;
    }

    /** The hypothesis ID this operator was compiled from. */
    public String hypothesisId() {
        return hypothesisId;
    }

    /** The hypothesis revision tag attached to every emitted transformation. */
    public String hypothesisRevision() {
        return hypothesisRevision;
    }

    /** A deterministic hash of the compiled patterns, for reproducibility checks. */
    public String provenanceHash() {
        return provenanceHash;
    }

    /** The left (source) pattern as a string, for serialisation and audit. */
    public String leftPatternText() {
        return leftPatternText;
    }

    /** The right (target) template as a string, for serialisation and audit. */
    public String rightPatternText() {
        return rightPatternText;
    }

    /**
     * Attempts to match the left pattern against {@code expression} and, on
     * success, instantiates the right template from the captured bindings.
     *
     * <p>Returns an empty list when:
     * <ul>
     *   <li>the expression cannot be parsed,</li>
     *   <li>the left pattern does not match,</li>
     *   <li>template instantiation fails, or</li>
     *   <li>the resulting expression is identical to the input (identity rewrite).</li>
     * </ul>
     * </p>
     */
    @Override
    public List<Transformation> generateCandidates(String expression) {
        if (expression == null || expression.isBlank()) {
            return List.of();
        }
        Optional<Map<String, Expr>> bindings = matcher.match(leftPattern, expression);
        if (bindings.isEmpty()) {
            return List.of();
        }
        Expr outputExpr;
        try {
            outputExpr = instantiator.instantiate(rightPattern, bindings.get());
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
        String formattedOutput = ExpressionFormatter.format(outputExpr);
        String formattedInput = normalizeWhitespace(expression);
        // Suppress identity rewrites
        if (canonicalKey(formattedOutput).equals(canonicalKey(formattedInput))) {
            return List.of();
        }
        String applicationKey = ruleId + ":" + hypothesisRevision + ":"
            + canonicalizer.stableHash(formattedInput) + "->" + canonicalizer.stableHash(formattedOutput);
        Transformation transformation = new Transformation(
            ruleId,
            formattedOutput,
            RewriteKind.NORMALIZE,
            true,
            -1,
            true,
            applicationKey
        );
        return List.of(transformation).stream()
            .limit(maxCandidates)
            .toList();
    }

    private String canonicalKey(String expression) {
        try {
            return canonicalizer.stableHash(expression);
        } catch (IllegalArgumentException ignored) {
            return expression;
        }
    }

    private static String normalizeWhitespace(String expression) {
        return expression.trim();
    }
}
