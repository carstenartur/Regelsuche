package de.regelsuche.search.learning;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.value.ExprValueFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Stable mathematical and alpha-normalized identity for one expression. */
public record ExpressionFingerprint(
    String valueHash,
    String alphaShapeHash,
    boolean parseable
) {
    public static final String VALUE_PREFIX = "value-v1:";
    public static final String ALPHA_PREFIX = "alpha-v1:";
    public static final String SYNTAX_PREFIX = "syntax-v1:";

    public ExpressionFingerprint {
        Objects.requireNonNull(valueHash, "valueHash");
        Objects.requireNonNull(alphaShapeHash, "alphaShapeHash");
    }

    public static ExpressionFingerprint of(
        String expression,
        ExpressionCanonicalizer canonicalizer
    ) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(canonicalizer, "canonicalizer");
        String normalized = normalize(expression);
        try {
            ExpressionParser parser = new ExpressionParser();
            Expr parsed = parser.parseTerm(normalized);
            String exact = valueHash(canonicalizer.canonicalize(parsed), canonicalizer, VALUE_PREFIX);
            Expr alpha = alphaNormalize(parsed, new LinkedHashMap<>());
            String shape = valueHash(canonicalizer.canonicalize(alpha), canonicalizer, ALPHA_PREFIX);
            return new ExpressionFingerprint(exact, shape, true);
        } catch (IllegalArgumentException exception) {
            String fallback = SYNTAX_PREFIX + sha256(normalized);
            return new ExpressionFingerprint(fallback, fallback, false);
        }
    }

    public static ExpressionFingerprint unknown(String stableIdentity) {
        String value = stableIdentity == null || stableIdentity.isBlank()
            ? SYNTAX_PREFIX + sha256("")
            : stableIdentity;
        return new ExpressionFingerprint(value, value, false);
    }

    private static String valueHash(
        Expr expression,
        ExpressionCanonicalizer canonicalizer,
        String prefix
    ) {
        try (ExprValueFactory factory = new ExprValueFactory()) {
            return prefix + sha256(factory.fromExpr(expression).key().encoded());
        }
    }

    private static Expr alphaNormalize(Expr expression, Map<String, String> variables) {
        if (expression instanceof VariableExpr variable) {
            String renamed = variables.computeIfAbsent(
                variable.name(), ignored -> "v" + variables.size());
            return new VariableExpr(renamed);
        }
        if (expression instanceof NumberExpr) {
            return expression;
        }
        if (expression instanceof FunctionExpr function) {
            return new FunctionExpr(
                function.name(),
                function.arguments().stream()
                    .map(argument -> alphaNormalize(argument, variables))
                    .toList());
        }
        BinaryExpr binary = (BinaryExpr) expression;
        return new BinaryExpr(
            alphaNormalize(binary.left(), variables),
            binary.operator(),
            alphaNormalize(binary.right(), variables));
    }

    private static String normalize(String expression) {
        return expression.trim().replaceAll("\\s+", " ");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
