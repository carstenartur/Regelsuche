package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
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
import java.util.TreeMap;

/**
 * Makes exact positive-integer monomial squares structurally visible.
 *
 * <p>For example, {@code x^4} becomes {@code (x^2)^2} and
 * {@code 4*x^2*y^2} becomes {@code (2*x*y)^2}. Every emitted candidate changes
 * only one AST occurrence, is reconstructed exactly from an integer monomial
 * square root and has no assumptions. Already explicit squares are ignored so
 * the operator has no immediate rewrite cycle.</p>
 */
public final class ExactMonomialSquareExposureOperator
        implements HypothesisOperator {
    public static final String RULE_ID = "expose_exact_monomial_square";
    private static final int DEFAULT_MAX_CANDIDATES = 16;

    private final ExpressionParser parser = new ExpressionParser();
    private final PerfectSquareStructurePreparationSolver.Budget budget;
    private final int maxCandidates;

    public ExactMonomialSquareExposureOperator() {
        this(
            PerfectSquareStructurePreparationSolver.Budget.DEFAULT,
            DEFAULT_MAX_CANDIDATES);
    }

    public ExactMonomialSquareExposureOperator(int maxCandidates) {
        this(
            PerfectSquareStructurePreparationSolver.Budget.DEFAULT,
            maxCandidates);
    }

    public ExactMonomialSquareExposureOperator(
        PerfectSquareStructurePreparationSolver.Budget budget,
        int maxCandidates
    ) {
        this.budget = Objects.requireNonNull(budget, "budget");
        this.maxCandidates = Math.max(0, maxCandidates);
    }

    @Override
    public List<Transformation> generateCandidates(String expression) {
        if (expression == null
                || expression.isBlank()
                || maxCandidates == 0) {
            return List.of();
        }
        Expr root;
        try {
            root = parser.parse(new InputRequest(InputType.TERM, expression))
                .terms()
                .getFirst();
        } catch (IllegalArgumentException exception) {
            return List.of();
        }

        String source = ExpressionFormatter.format(root);
        String sourceHash = syntaxHash(source);
        Map<String, Transformation> retained = new LinkedHashMap<>();
        Deque<PositionedNode> pending = new ArrayDeque<>();
        pending.push(new PositionedNode(root, List.of()));
        while (!pending.isEmpty() && retained.size() < maxCandidates) {
            PositionedNode positioned = pending.pop();
            Candidate candidate = candidate(positioned.expression());
            if (candidate != null) {
                Expr rewritten = replaceAt(
                    root,
                    positioned.path(),
                    0,
                    square(candidate.root().toExpression()));
                String transformed = ExpressionFormatter.format(rewritten);
                String position = positionKey(positioned.path());
                retained.putIfAbsent(
                    transformed,
                    new Transformation(
                        RULE_ID,
                        transformed,
                        RewriteKind.NORMALIZE,
                        true,
                        1,
                        true,
                        RULE_ID + ":" + sourceHash + ":" + position + ":"
                            + candidate.original().descriptor() + "->"
                            + candidate.root().descriptor()));
            }
            if (retained.size() < maxCandidates) {
                pushChildren(pending, positioned);
            }
        }
        return List.copyOf(retained.values());
    }

    private Candidate candidate(Expr expression) {
        if (expression instanceof NumberExpr || isExplicitSquare(expression)) {
            return null;
        }
        ExactPositiveMonomial.Parser monomialParser =
            new ExactPositiveMonomial.Parser(new ExactPositiveMonomial.Limits(
                budget.maxFactors(),
                budget.maxExponent(),
                budget.maxCoefficient()));
        ExactPositiveMonomial.ParseResult parsed =
            monomialParser.parse(expression);
        if (!parsed.supported()) {
            return null;
        }
        ExactPositiveMonomial original = parsed.monomial();
        ExactPositiveMonomial root = exactSquareRoot(original);
        return root == null ? null : new Candidate(original, root);
    }

    private static ExactPositiveMonomial exactSquareRoot(
        ExactPositiveMonomial monomial
    ) {
        long coefficientRoot = perfectSquareRoot(monomial.coefficient());
        if (coefficientRoot < 1
                || monomial.powers().values().stream()
                    .anyMatch(exponent -> exponent % 2 != 0)) {
            return null;
        }
        TreeMap<String, Integer> powers = new TreeMap<>();
        monomial.powers().forEach((name, exponent) -> {
            int rootExponent = exponent / 2;
            if (rootExponent > 0) {
                powers.put(name, rootExponent);
            }
        });
        return new ExactPositiveMonomial(coefficientRoot, powers);
    }

    private static long perfectSquareRoot(long value) {
        long root = (long) Math.sqrt(value);
        while ((root + 1) * (root + 1) <= value) {
            root++;
        }
        while (root * root > value) {
            root--;
        }
        return root * root == value ? root : -1;
    }

    private static Expr square(Expr expression) {
        return new BinaryExpr(
            expression,
            BinaryOperator.POW,
            new NumberExpr(2));
    }

    private static boolean isExplicitSquare(Expr expression) {
        return expression instanceof BinaryExpr power
            && power.operator() == BinaryOperator.POW
            && power.right() instanceof NumberExpr exponent
            && Double.compare(exponent.value(), 2.0) == 0;
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

    private static String syntaxHash(String expression) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(expression.getBytes(StandardCharsets.UTF_8));
            return "syntax-v1:" + HexFormat.of().formatHex(digest, 0, 12);
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

    private record Candidate(
        ExactPositiveMonomial original,
        ExactPositiveMonomial root
    ) {
        private Candidate {
            original = Objects.requireNonNull(original, "original");
            root = Objects.requireNonNull(root, "root");
        }
    }
}
