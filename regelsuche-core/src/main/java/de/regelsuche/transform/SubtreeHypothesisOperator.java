package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies a root-oriented {@link HypothesisOperator} at every AST occurrence.
 *
 * <p>Learned dynamic rules intentionally match one complete expression. This
 * adapter preserves that small execution surface while making the rule usable
 * inside an unseen surrounding expression. Every emitted transformation changes
 * exactly one occurrence and retains the delegated rule identity, assumptions,
 * provenance metadata, and primitive-step accounting.</p>
 */
public final class SubtreeHypothesisOperator implements HypothesisOperator {
    private static final int DEFAULT_MAX_CANDIDATES = 64;

    private final HypothesisOperator delegate;
    private final int maxCandidates;
    private final ExpressionParser parser = new ExpressionParser();

    public SubtreeHypothesisOperator(HypothesisOperator delegate) {
        this(delegate, DEFAULT_MAX_CANDIDATES);
    }

    public SubtreeHypothesisOperator(
        HypothesisOperator delegate,
        int maxCandidates
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.maxCandidates = Math.max(0, maxCandidates);
    }

    @Override
    public List<Transformation> generateCandidates(String expression) {
        if (expression == null || expression.isBlank() || maxCandidates == 0) {
            return List.of();
        }
        Expr root;
        try {
            root = parser.parseTerm(expression);
        } catch (IllegalArgumentException exception) {
            return List.of();
        }

        String formattedSource = ExpressionFormatter.format(root);
        Map<String, Transformation> retained = new LinkedHashMap<>();
        Deque<PositionedNode> pending = new ArrayDeque<>();
        pending.push(new PositionedNode(root, List.of()));
        while (!pending.isEmpty() && retained.size() < maxCandidates) {
            PositionedNode positioned = pending.pop();
            String subtree = ExpressionFormatter.format(positioned.expression());
            List<Transformation> delegatedCandidates =
                delegate.generateCandidates(subtree);
            if (delegatedCandidates != null) {
                for (Transformation delegated : delegatedCandidates) {
                    if (retained.size() >= maxCandidates) {
                        break;
                    }
                    if (delegated == null) {
                        continue;
                    }
                    Expr replacement;
                    try {
                        replacement = parser.parseTerm(
                            delegated.transformedExpression());
                    } catch (IllegalArgumentException exception) {
                        continue;
                    }
                    if (replacement.equals(positioned.expression())) {
                        continue;
                    }

                    Expr rewritten = replaceAt(
                        root,
                        positioned.path(),
                        0,
                        replacement);
                    String transformed = ExpressionFormatter.format(rewritten);
                    if (transformed.equals(formattedSource)) {
                        continue;
                    }
                    String key = applicationKey(
                        delegated,
                        formattedSource,
                        positioned.path(),
                        transformed);
                    retained.putIfAbsent(
                        key,
                        new Transformation(
                            delegated.rule(),
                            transformed,
                            delegated.kind(),
                            delegated.mayIncreaseComplexity(),
                            delegated.estimatedCostDelta(),
                            delegated.equivalencePreservingByConstruction(),
                            key,
                            delegated.assumptions(),
                            delegated.packId(),
                            delegated.license(),
                            delegated.primitiveRuleIds()));
                }
            }
            if (retained.size() < maxCandidates) {
                pushChildren(pending, positioned);
            }
        }
        return List.copyOf(retained.values());
    }

    private static String applicationKey(
        Transformation delegated,
        String source,
        List<Integer> path,
        String transformed
    ) {
        return "subtree-v1:"
            + delegated.rule()
            + ":" + positionKey(path)
            + ":delegate-" + digest(delegated.applicationKey())
            + ":transition-" + digest(source + "\u0000" + transformed);
    }

    private static void pushChildren(
        Deque<PositionedNode> pending,
        PositionedNode positioned
    ) {
        Expr expression = positioned.expression();
        List<Integer> path = positioned.path();
        if (expression instanceof BinaryExpr binary) {
            pending.push(new PositionedNode(
                binary.right(),
                append(path, 1)));
            pending.push(new PositionedNode(
                binary.left(),
                append(path, 0)));
        } else if (expression instanceof FunctionExpr function) {
            for (int index = function.arguments().size() - 1;
                    index >= 0;
                    index--) {
                pending.push(new PositionedNode(
                    function.arguments().get(index),
                    append(path, index)));
            }
        }
    }

    private static List<Integer> append(List<Integer> path, int value) {
        List<Integer> result = new ArrayList<>(path);
        result.add(value);
        return List.copyOf(result);
    }

    private static Expr replaceAt(
        Expr expression,
        List<Integer> path,
        int index,
        Expr replacement
    ) {
        if (index == path.size()) {
            return replacement;
        }
        int child = path.get(index);
        if (expression instanceof BinaryExpr binary) {
            if (child == 0) {
                return new BinaryExpr(
                    replaceAt(binary.left(), path, index + 1, replacement),
                    binary.operator(),
                    binary.right());
            }
            if (child == 1) {
                return new BinaryExpr(
                    binary.left(),
                    binary.operator(),
                    replaceAt(binary.right(), path, index + 1, replacement));
            }
        } else if (expression instanceof FunctionExpr function
                && child >= 0
                && child < function.arguments().size()) {
            List<Expr> arguments = new ArrayList<>(function.arguments());
            arguments.set(
                child,
                replaceAt(
                    arguments.get(child),
                    path,
                    index + 1,
                    replacement));
            return new FunctionExpr(function.name(), arguments);
        }
        throw new IllegalArgumentException("invalid AST occurrence path");
    }

    private static String positionKey(List<Integer> path) {
        if (path.isEmpty()) {
            return "root";
        }
        return path.stream()
            .map(String::valueOf)
            .collect(java.util.stream.Collectors.joining("."));
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record PositionedNode(Expr expression, List<Integer> path) {
        private PositionedNode {
            expression = Objects.requireNonNull(expression, "expression");
            path = List.copyOf(Objects.requireNonNull(path, "path"));
        }
    }
}
