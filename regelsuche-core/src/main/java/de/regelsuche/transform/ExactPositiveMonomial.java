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

/** Exact positive-integer monomial representation used by preparation solvers. */
final class ExactPositiveMonomial {
    private final long coefficient;
    private final SortedMap<String, Integer> powers;

    private ExactPositiveMonomial(
        long coefficient,
        Map<String, Integer> powers
    ) {
        if (coefficient < 1) {
            throw new IllegalArgumentException(
                "monomial coefficient must be positive");
        }
        TreeMap<String, Integer> copy = new TreeMap<>(
            Objects.requireNonNull(powers, "powers"));
        if (copy.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null
                    || entry.getKey().isBlank()
                    || entry.getValue() == null
                    || entry.getValue() < 1)) {
            throw new IllegalArgumentException(
                "monomial powers require names and positive exponents");
        }
        this.coefficient = coefficient;
        this.powers = Collections.unmodifiableSortedMap(copy);
    }

    long coefficient() {
        return coefficient;
    }

    SortedMap<String, Integer> powers() {
        return powers;
    }

    boolean isOne() {
        return coefficient == 1 && powers.isEmpty();
    }

    ExactPositiveMonomial gcd(ExactPositiveMonomial other) {
        TreeMap<String, Integer> commonPowers = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : powers.entrySet()) {
            Integer otherExponent = other.powers.get(entry.getKey());
            if (otherExponent != null) {
                commonPowers.put(
                    entry.getKey(),
                    Math.min(entry.getValue(), otherExponent));
            }
        }
        return new ExactPositiveMonomial(
            greatestCommonDivisor(coefficient, other.coefficient),
            commonPowers);
    }

    ExactPositiveMonomial divideExactly(ExactPositiveMonomial divisor) {
        if (coefficient % divisor.coefficient != 0) {
            return null;
        }
        TreeMap<String, Integer> remainderPowers = new TreeMap<>(powers);
        for (Map.Entry<String, Integer> entry : divisor.powers.entrySet()) {
            Integer exponent = remainderPowers.get(entry.getKey());
            if (exponent == null || exponent < entry.getValue()) {
                return null;
            }
            int remaining = exponent - entry.getValue();
            if (remaining == 0) {
                remainderPowers.remove(entry.getKey());
            } else {
                remainderPowers.put(entry.getKey(), remaining);
            }
        }
        return new ExactPositiveMonomial(
            coefficient / divisor.coefficient,
            remainderPowers);
    }

    ExactPositiveMonomial multiply(ExactPositiveMonomial other) {
        long productCoefficient;
        try {
            productCoefficient = Math.multiplyExact(
                coefficient,
                other.coefficient);
        } catch (ArithmeticException exception) {
            return null;
        }
        TreeMap<String, Integer> productPowers = new TreeMap<>(powers);
        for (Map.Entry<String, Integer> entry : other.powers.entrySet()) {
            int current = productPowers.getOrDefault(entry.getKey(), 0);
            int productExponent;
            try {
                productExponent = Math.addExact(current, entry.getValue());
            } catch (ArithmeticException exception) {
                return null;
            }
            productPowers.put(entry.getKey(), productExponent);
        }
        return new ExactPositiveMonomial(productCoefficient, productPowers);
    }

    Expr toExpression() {
        List<Expr> factors = new ArrayList<>();
        if (coefficient != 1 || powers.isEmpty()) {
            factors.add(new NumberExpr(coefficient));
        }
        for (Map.Entry<String, Integer> entry : powers.entrySet()) {
            Expr variable = new VariableExpr(entry.getKey());
            factors.add(entry.getValue() == 1
                ? variable
                : new BinaryExpr(
                    variable,
                    BinaryOperator.POW,
                    new NumberExpr(entry.getValue())));
        }
        Expr result = factors.getFirst();
        for (int index = 1; index < factors.size(); index++) {
            result = new BinaryExpr(
                result,
                BinaryOperator.MUL,
                factors.get(index));
        }
        return result;
    }

    String descriptor() {
        StringBuilder descriptor = new StringBuilder();
        descriptor.append(coefficient);
        for (Map.Entry<String, Integer> entry : powers.entrySet()) {
            descriptor.append('|')
                .append(entry.getKey().length())
                .append(':')
                .append(entry.getKey())
                .append('^')
                .append(entry.getValue());
        }
        return descriptor.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExactPositiveMonomial monomial
            && coefficient == monomial.coefficient
            && powers.equals(monomial.powers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(coefficient, powers);
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

    record Limits(
        int maxFactors,
        int maxExponent,
        long maxCoefficient
    ) {
        Limits {
            if (maxFactors < 0 || maxExponent < 1 || maxCoefficient < 1) {
                throw new IllegalArgumentException(
                    "factor limit must be non-negative and algebra limits positive");
            }
        }
    }

    enum ParseStatus {
        SUPPORTED,
        UNSUPPORTED,
        INCONCLUSIVE
    }

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
                    "only supported parse results retain a monomial");
            }
        }

        static ParseResult supported(ExactPositiveMonomial monomial) {
            return new ParseResult(
                ParseStatus.SUPPORTED,
                Objects.requireNonNull(monomial, "monomial"),
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
                return parseVariable(variable);
            }
            if (expression instanceof BinaryExpr binary
                    && binary.operator() == BinaryOperator.POW) {
                return parsePower(binary);
            }
            if (expression instanceof BinaryExpr binary
                    && binary.operator() == BinaryOperator.MUL) {
                return parseProduct(binary);
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
            if (!consumeFactor()) {
                return factorLimit();
            }
            long value = exactPositiveInteger(number.value());
            if (value < 1) {
                return ParseResult.unsupported(
                    "coefficient-is-not-a-positive-exact-integer");
            }
            if (value > limits.maxCoefficient()) {
                return ParseResult.inconclusive(
                    "monomial-coefficient-limit-exhausted");
            }
            return ParseResult.supported(new ExactPositiveMonomial(
                value,
                Map.of()));
        }

        private ParseResult parseVariable(VariableExpr variable) {
            if (!consumeFactor()) {
                return factorLimit();
            }
            return ParseResult.supported(new ExactPositiveMonomial(
                1,
                Map.of(variable.name(), 1)));
        }

        private ParseResult parsePower(BinaryExpr power) {
            if (!(power.left() instanceof VariableExpr variable)
                    || !(power.right() instanceof NumberExpr exponentValue)) {
                return ParseResult.unsupported(
                    "power-is-not-a-positive-integer-variable-power");
            }
            if (!consumeFactor()) {
                return factorLimit();
            }
            long exponent = exactPositiveInteger(exponentValue.value());
            if (exponent < 1) {
                return ParseResult.unsupported(
                    "variable-power-exponent-is-not-a-positive-exact-integer");
            }
            if (exponent > limits.maxExponent()) {
                return ParseResult.inconclusive(
                    "monomial-exponent-limit-exhausted");
            }
            return ParseResult.supported(new ExactPositiveMonomial(
                1,
                Map.of(variable.name(), Math.toIntExact(exponent))));
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
            if (combined.powers().values().stream().anyMatch(exponent ->
                    exponent > limits.maxExponent())) {
                return ParseResult.inconclusive(
                    "monomial-exponent-limit-exhausted");
            }
            return ParseResult.supported(combined);
        }

        private boolean consumeFactor() {
            if (inspectedFactors >= limits.maxFactors()) {
                return false;
            }
            inspectedFactors++;
            return true;
        }

        private static ParseResult factorLimit() {
            return ParseResult.inconclusive(
                "monomial-factor-limit-exhausted");
        }

        private static long exactPositiveInteger(double value) {
            if (!Double.isFinite(value)
                    || value < 1
                    || value != Math.rint(value)
                    || value > Long.MAX_VALUE) {
                return -1;
            }
            return (long) value;
        }
    }
}
