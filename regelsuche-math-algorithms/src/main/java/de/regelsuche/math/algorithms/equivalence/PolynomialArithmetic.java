package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import java.util.LinkedHashMap;
import java.util.Optional;

final class PolynomialArithmetic {
    private static final int DEFAULT_PARSE_CACHE_CAPACITY = 2_048;
    private final ExpressionParser parser = new ExpressionParser();
    private final int parseCacheCapacity;
    private final LinkedHashMap<String, Optional<Polynomial>> parseCache =
        new LinkedHashMap<>(64, 0.75f, true);

    PolynomialArithmetic() {
        this(DEFAULT_PARSE_CACHE_CAPACITY);
    }

    PolynomialArithmetic(int parseCacheCapacity) {
        if (parseCacheCapacity < 0) {
            throw new IllegalArgumentException("parseCacheCapacity must be non-negative");
        }
        this.parseCacheCapacity = parseCacheCapacity;
    }

    Optional<Polynomial> parse(String expression) {
        if (parseCacheCapacity == 0) {
            return parseUncached(expression);
        }
        synchronized (parseCache) {
            Optional<Polynomial> cached = parseCache.get(expression);
            if (cached != null) {
                return cached;
            }
        }

        Optional<Polynomial> parsed = parseUncached(expression);
        synchronized (parseCache) {
            Optional<Polynomial> cached = parseCache.get(expression);
            if (cached != null) {
                return cached;
            }
            parseCache.put(expression, parsed);
            while (parseCache.size() > parseCacheCapacity) {
                String eldest = parseCache.keySet().iterator().next();
                parseCache.remove(eldest);
            }
            return parsed;
        }
    }

    int parseCacheSize() {
        synchronized (parseCache) {
            return parseCache.size();
        }
    }

    int parseCacheCapacity() {
        return parseCacheCapacity;
    }

    private Optional<Polynomial> parseUncached(String expression) {
        try {
            Expr expr = parser.parseTerm(expression);
            return asPolynomial(expr);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private Optional<Polynomial> asPolynomial(Expr expression) {
        if (expression instanceof NumberExpr numberExpr) {
            return Optional.of(Polynomial.constant(Rational.fromDouble(numberExpr.value())));
        }
        if (expression instanceof VariableExpr variableExpr) {
            return Optional.of(Polynomial.variable(variableExpr.name()));
        }
        if (expression instanceof FunctionExpr) {
            return Optional.empty();
        }
        BinaryExpr binaryExpr = (BinaryExpr) expression;
        Optional<Polynomial> left = asPolynomial(binaryExpr.left());
        Optional<Polynomial> right = asPolynomial(binaryExpr.right());
        if (left.isEmpty() || right.isEmpty()) {
            return Optional.empty();
        }
        return switch (binaryExpr.operator()) {
            case ADD -> Optional.of(left.orElseThrow().add(right.orElseThrow()));
            case SUB -> Optional.of(left.orElseThrow().subtract(right.orElseThrow()));
            case MUL -> Optional.of(left.orElseThrow().multiply(right.orElseThrow()));
            case DIV -> Optional.empty();
            case POW -> integerExponent(binaryExpr.right()).map(exponent -> left.orElseThrow().pow(exponent));
        };
    }

    private Optional<Integer> integerExponent(Expr expression) {
        if (!(expression instanceof NumberExpr numberExpr)) {
            return Optional.empty();
        }
        double value = numberExpr.value();
        int exponent = (int) value;
        if (Math.abs(value - exponent) > 1e-9 || exponent < 0 || exponent > 20) {
            return Optional.empty();
        }
        return Optional.of(exponent);
    }

    record LinearEquation(Rational coefficient, Polynomial rest) {
    }
}
