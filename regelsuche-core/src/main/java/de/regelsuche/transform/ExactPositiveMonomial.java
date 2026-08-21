package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/** Exact positive-integer monomial used by bounded preparation solvers. */
record ExactPositiveMonomial(
    long coefficient,
    SortedMap<String, Integer> powers
) {
    static final long MAX_EXACT_DOUBLE_INTEGER = (1L << 53) - 1;

    ExactPositiveMonomial(long coefficient, Map<String, Integer> powers) {
        this(coefficient, new TreeMap<>(Objects.requireNonNull(powers, "powers")));
    }

    ExactPositiveMonomial {
        if (coefficient < 1
                || coefficient > MAX_EXACT_DOUBLE_INTEGER
                || powers == null
                || powers.entrySet().stream().anyMatch(entry ->
                    entry.getKey() == null
                        || entry.getKey().isBlank()
                        || entry.getValue() == null
                        || entry.getValue() < 1)) {
            throw new IllegalArgumentException(
                "monomials require a safely representable positive coefficient and powers");
        }
        powers = Collections.unmodifiableSortedMap(new TreeMap<>(powers));
    }

    boolean isOne() {
        return coefficient == 1 && powers.isEmpty();
    }

    ExactPositiveMonomial gcd(ExactPositiveMonomial other) {
        TreeMap<String, Integer> common = new TreeMap<>();
        powers.forEach((name, exponent) -> {
            Integer candidate = other.powers.get(name);
            if (candidate != null) {
                common.put(name, Math.min(exponent, candidate));
            }
        });
        return new ExactPositiveMonomial(
            greatestCommonDivisor(coefficient, other.coefficient), common);
    }

    ExactPositiveMonomial divideExactly(ExactPositiveMonomial divisor) {
        if (coefficient % divisor.coefficient != 0) {
            return null;
        }
        TreeMap<String, Integer> remainder = new TreeMap<>(powers);
        for (Map.Entry<String, Integer> factor : divisor.powers.entrySet()) {
            Integer exponent = remainder.get(factor.getKey());
            if (exponent == null || exponent < factor.getValue()) {
                return null;
            }
            int value = exponent - factor.getValue();
            if (value == 0) {
                remainder.remove(factor.getKey());
            } else {
                remainder.put(factor.getKey(), value);
            }
        }
        return new ExactPositiveMonomial(
            coefficient / divisor.coefficient, remainder);
    }

    ExactPositiveMonomial multiply(ExactPositiveMonomial other) {
        long product;
        try {
            product = Math.multiplyExact(coefficient, other.coefficient);
        } catch (ArithmeticException exception) {
            return null;
        }
        if (product > MAX_EXACT_DOUBLE_INTEGER) {
            return null;
        }
        TreeMap<String, Integer> combined = new TreeMap<>(powers);
        for (Map.Entry<String, Integer> factor : other.powers.entrySet()) {
            try {
                combined.merge(factor.getKey(), factor.getValue(), Math::addExact);
            } catch (ArithmeticException exception) {
                return null;
            }
        }
        return new ExactPositiveMonomial(product, combined);
    }

    Expr toExpression() {
        List<Expr> factors = new ArrayList<>();
        if (coefficient != 1 || powers.isEmpty()) {
            factors.add(new NumberExpr(coefficient));
        }
        powers.forEach((name, exponent) -> {
            Expr variable = new VariableExpr(name);
            factors.add(exponent == 1 ? variable : new BinaryExpr(
                variable, BinaryOperator.POW, new NumberExpr(exponent)));
        });
        Expr result = factors.getFirst();
        for (int index = 1; index < factors.size(); index++) {
            result = new BinaryExpr(
                result, BinaryOperator.MUL, factors.get(index));
        }
        return result;
    }

    String descriptor() {
        StringBuilder result = new StringBuilder().append(coefficient);
        powers.forEach((name, exponent) -> result.append('|')
            .append(name.length()).append(':').append(name)
            .append('^').append(exponent));
        return result.toString();
    }

    private static long greatestCommonDivisor(long left, long right) {
        long a = left;
        long b = right;
        while (b != 0) {
            long remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }

    record Limits(int maxFactors, int maxExponent, long maxCoefficient) {
        Limits {
            if (maxFactors < 0
                    || maxExponent < 1
                    || maxCoefficient < 1
                    || maxCoefficient > MAX_EXACT_DOUBLE_INTEGER) {
                throw new IllegalArgumentException(
                    "limits must remain inside the safe positive double-integer fragment");
            }
        }
    }

    enum ParseStatus { SUPPORTED, UNSUPPORTED, INCONCLUSIVE }

    record ParseResult(
        ParseStatus status,
        ExactPositiveMonomial monomial,
        String detail
    ) {
        ParseResult {
            status = Objects.requireNonNull(status, "status");
            detail = detail == null ? "" : detail;
            if ((status == ParseStatus.SUPPORTED) != (monomial != null)) {
                throw new IllegalArgumentException(
                    "only supported results retain a monomial");
            }
        }

        static ParseResult supported(ExactPositiveMonomial value) {
            return new ParseResult(
                ParseStatus.SUPPORTED,
                Objects.requireNonNull(value, "value"),
                "");
        }

        static ParseResult unsupported(String detail) {
            return new ParseResult(ParseStatus.UNSUPPORTED, null, detail);
        }

        static ParseResult inconclusive(String detail) {
            return new ParseResult(ParseStatus.INCONCLUSIVE, null, detail);
        }

        boolean supported() {
            return status == ParseStatus.SUPPORTED;
        }
    }

    static final class Parser {
        private final Limits limits;
        private int inspectedFactors;

        Parser(Limits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
        }

        ParseResult parse(Expr expression) {
            if (expression instanceof NumberExpr number) {
                return parseNumber(number);
            }
            if (expression instanceof VariableExpr variable) {
                return atomic(1, Map.of(variable.name(), 1));
            }
            if (expression instanceof BinaryExpr binary) {
                if (binary.operator() == BinaryOperator.POW) {
                    return parsePower(binary);
                }
                if (binary.operator() == BinaryOperator.MUL) {
                    return parseProduct(binary);
                }
            }
            return ParseResult.unsupported(
                "term-is-outside-positive-integer-monomial-fragment");
        }

        int inspectedFactors() {
            return inspectedFactors;
        }

        int remainingFactors() {
            return limits.maxFactors() - inspectedFactors;
        }

        private ParseResult parseNumber(NumberExpr number) {
            long value = exactPositiveInteger(number.value());
            if (value < 1) {
                return ParseResult.unsupported(
                    "coefficient-is-not-a-safely-representable-positive-integer");
            }
            return value > limits.maxCoefficient()
                ? ParseResult.inconclusive("monomial-coefficient-limit-exhausted")
                : atomic(value, Map.of());
        }

        private ParseResult parsePower(BinaryExpr power) {
            if (!(power.left() instanceof VariableExpr variable)
                    || !(power.right() instanceof NumberExpr exponentValue)) {
                return ParseResult.unsupported(
                    "power-is-not-a-positive-integer-variable-power");
            }
            long exponent = exactPositiveInteger(exponentValue.value());
            if (exponent < 1) {
                return ParseResult.unsupported(
                    "variable-power-exponent-is-not-a-positive-exact-integer");
            }
            return exponent > limits.maxExponent()
                ? ParseResult.inconclusive("monomial-exponent-limit-exhausted")
                : atomic(1, Map.of(variable.name(), Math.toIntExact(exponent)));
        }

        private ParseResult atomic(long coefficient, Map<String, Integer> powers) {
            if (inspectedFactors >= limits.maxFactors()) {
                return ParseResult.inconclusive("monomial-factor-limit-exhausted");
            }
            inspectedFactors++;
            return ParseResult.supported(
                new ExactPositiveMonomial(coefficient, powers));
        }

        private ParseResult parseProduct(BinaryExpr product) {
            ParseResult left = parse(product.left());
            if (!left.supported()) {
                return left;
            }
            ParseResult right = parse(product.right());
            if (!right.supported()) {
                return right;
            }
            ExactPositiveMonomial combined =
                left.monomial().multiply(right.monomial());
            if (combined == null
                    || combined.coefficient() > limits.maxCoefficient()) {
                return ParseResult.inconclusive(
                    "monomial-coefficient-limit-exhausted");
            }
            return combined.powers().values().stream()
                    .anyMatch(exponent -> exponent > limits.maxExponent())
                ? ParseResult.inconclusive("monomial-exponent-limit-exhausted")
                : ParseResult.supported(combined);
        }

        private static long exactPositiveInteger(double value) {
            return Double.isFinite(value)
                    && value >= 1
                    && value == Math.rint(value)
                    && value <= MAX_EXACT_DOUBLE_INTEGER
                ? (long) value
                : -1;
        }
    }
}
