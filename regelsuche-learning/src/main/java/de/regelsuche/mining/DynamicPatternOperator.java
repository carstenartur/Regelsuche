package de.regelsuche.mining;

import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.HypothesisOperator;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A quarantined, dynamically compiled hypothesis operator that executes an
 * AST rewrite rule specified entirely by a left-hand pattern and a right-hand
 * template, without requiring a hand-written Java operator class.
 */
public final class DynamicPatternOperator implements HypothesisOperator {

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

    public String ruleId() {
        return ruleId;
    }

    public String hypothesisId() {
        return hypothesisId;
    }

    public String hypothesisRevision() {
        return hypothesisRevision;
    }

    public String provenanceHash() {
        return provenanceHash;
    }

    public String leftPatternText() {
        return leftPatternText;
    }

    public String rightPatternText() {
        return rightPatternText;
    }

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
        String formattedInput = formatted(expression);
        // A rewrite is an identity only when its parsed syntax is unchanged. Mathematical
        // canonical equality must not suppress useful simplifications such as (A + 0) * 1 -> A.
        if (formattedOutput.equals(formattedInput)) {
            return List.of();
        }
        String applicationKey = ruleId + ":" + hypothesisRevision + ":"
            + syntaxHash(formattedInput) + "->" + syntaxHash(formattedOutput);
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

    private String formatted(String expression) {
        try {
            return ExpressionFormatter.format(parser.parseTerm(expression));
        } catch (IllegalArgumentException ignored) {
            return expression.trim().replaceAll("\\s+", " ");
        }
    }

    private static String syntaxHash(String expression) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(expression.getBytes(StandardCharsets.UTF_8));
            return "syntax-v1:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}