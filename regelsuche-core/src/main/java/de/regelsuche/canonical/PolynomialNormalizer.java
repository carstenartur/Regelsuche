package de.regelsuche.canonical;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Safe polynomial normalizer for expressions over numeric constants, variables,
 * addition, subtraction, multiplication and non-negative integer powers.
 */
public final class PolynomialNormalizer {
    private static final int MAX_EXPANDED_TERMS = 1_000;

    public Optional<Expr> normalize(Expr expression) {
        Polynomial polynomial = toPolynomial(expression);
        if (polynomial == null) {
            return Optional.empty();
        }
        Expr normalized = polynomial.toExpr();
        return normalized == null ? Optional.empty() : Optional.of(normalized);
    }

    private Polynomial toPolynomial(Expr expression) {
        if (expression instanceof NumberExpr number) {
            return Polynomial.constant(number.value());
        }
        if (expression instanceof VariableExpr variable) {
            return Polynomial.monomial(1, Monomial.variable(variable.name()));
        }
        if (!(expression instanceof BinaryExpr binary)) {
            return null;
        }
        return switch (binary.operator()) {
            case ADD -> combine(binary.left(), binary.right(), 1);
            case SUB -> combine(binary.left(), binary.right(), -1);
            case MUL -> multiply(binary.left(), binary.right());
            case POW -> power(binary.left(), binary.right());
            case DIV -> null;
        };
    }

    private Polynomial combine(Expr left, Expr right, int rightSign) {
        Polynomial leftPolynomial = toPolynomial(left);
        Polynomial rightPolynomial = toPolynomial(right);
        if (leftPolynomial == null || rightPolynomial == null) {
            return null;
        }
        return leftPolynomial.add(rightPolynomial.scale(rightSign));
    }

    private Polynomial multiply(Expr left, Expr right) {
        Polynomial leftPolynomial = toPolynomial(left);
        Polynomial rightPolynomial = toPolynomial(right);
        if (leftPolynomial == null || rightPolynomial == null) {
            return null;
        }
        return leftPolynomial.multiply(rightPolynomial);
    }

    private Polynomial power(Expr base, Expr exponent) {
        if (!(exponent instanceof NumberExpr number) || !isNonNegativeInteger(number.value())) {
            return null;
        }
        int exponentValue = (int) number.value();
        Polynomial basePolynomial = toPolynomial(base);
        if (basePolynomial == null) {
            return null;
        }
        return basePolynomial.pow(exponentValue);
    }

    private boolean isNonNegativeInteger(double value) {
        return value >= 0 && value <= Integer.MAX_VALUE && Math.rint(value) == value;
    }

    private record Monomial(Map<String, Integer> powers) {
        private Monomial {
            powers = Collections.unmodifiableMap(new TreeMap<>(powers));
        }

        private static Monomial constant() {
            return new Monomial(Map.of());
        }

        private static Monomial variable(String name) {
            return new Monomial(Map.of(name, 1));
        }

        private Monomial multiply(Monomial other) {
            Map<String, Integer> result = new TreeMap<>(powers);
            for (Map.Entry<String, Integer> entry : other.powers.entrySet()) {
                try {
                    result.merge(entry.getKey(), entry.getValue(), Math::addExact);
                } catch (ArithmeticException ex) {
                    return null;
                }
            }
            result.values().removeIf(value -> value == 0);
            return new Monomial(result);
        }

        private Monomial pow(int exponent) {
            Map<String, Integer> result = new TreeMap<>();
            for (Map.Entry<String, Integer> entry : powers.entrySet()) {
                try {
                    result.put(entry.getKey(), Math.multiplyExact(entry.getValue(), exponent));
                } catch (ArithmeticException ex) {
                    return null;
                }
            }
            return new Monomial(result);
        }

        private int degree() {
            int degree = 0;
            for (int exponent : powers.values()) {
                degree += exponent;
            }
            return degree;
        }

        private Expr toExpr() {
            if (powers.isEmpty()) {
                return new NumberExpr(1);
            }
            List<Expr> factors = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : powers.entrySet()) {
                Expr variable = new VariableExpr(entry.getKey());
                factors.add(entry.getValue() == 1
                    ? variable
                    : new BinaryExpr(variable, BinaryOperator.POW, new NumberExpr(entry.getValue())));
            }
            return leftAssociate(factors, BinaryOperator.MUL);
        }

        private String sortKey() {
            if (powers.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<String, Integer> entry : powers.entrySet()) {
                if (!builder.isEmpty()) {
                    builder.append('*');
                }
                builder.append(entry.getKey());
                if (entry.getValue() != 1) {
                    builder.append('^').append(entry.getValue());
                }
            }
            return builder.toString();
        }
    }

    private record Rational(BigInteger numerator, BigInteger denominator) {
        private Rational {
            if (denominator.signum() == 0) {
                throw new IllegalArgumentException("denominator must be non-zero");
            }
            if (denominator.signum() < 0) {
                numerator = numerator.negate();
                denominator = denominator.negate();
            }
            BigInteger gcd = numerator.gcd(denominator);
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }

        private static Rational of(long value) {
            return new Rational(BigInteger.valueOf(value), BigInteger.ONE);
        }

        private static Rational fromDouble(double value) {
            if (!Double.isFinite(value)) {
                return null;
            }
            BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
            BigInteger numerator = decimal.unscaledValue();
            int scale = decimal.scale();
            if (scale < 0) {
                numerator = numerator.multiply(BigInteger.TEN.pow(-scale));
                return new Rational(numerator, BigInteger.ONE);
            }
            return new Rational(numerator, BigInteger.TEN.pow(scale));
        }

        private Rational add(Rational other) {
            return new Rational(
                numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
        }

        private Rational multiply(Rational other) {
            return new Rational(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
        }

        private Rational negate() {
            return new Rational(numerator.negate(), denominator);
        }

        private Rational abs() {
            return signum() < 0 ? negate() : this;
        }

        private boolean isZero() {
            return numerator.signum() == 0;
        }

        private boolean isOne() {
            return numerator.equals(denominator);
        }

        private int signum() {
            return numerator.signum();
        }

        private Double toFiniteDouble() {
            double value = numerator.doubleValue() / denominator.doubleValue();
            return Double.isFinite(value) ? value : null;
        }
    }

    private static final class Polynomial {
        private static final Comparator<Map.Entry<Monomial, Rational>> TERM_ORDER = Comparator
            .<Map.Entry<Monomial, Rational>>comparingInt(entry -> entry.getKey().degree())
            .reversed()
            .thenComparing(entry -> entry.getKey().sortKey());

        private final Map<Monomial, Rational> terms;

        private Polynomial(Map<Monomial, Rational> terms) {
            this.terms = normalizedTerms(terms);
        }

        private static Polynomial constant(double value) {
            Rational coefficient = Rational.fromDouble(value);
            return coefficient == null ? null : monomial(coefficient, Monomial.constant());
        }

        private static Polynomial monomial(long coefficient, Monomial monomial) {
            return monomial(Rational.of(coefficient), monomial);
        }

        private static Polynomial monomial(Rational coefficient, Monomial monomial) {
            Map<Monomial, Rational> terms = new LinkedHashMap<>();
            terms.put(monomial, coefficient);
            return new Polynomial(terms);
        }

        private Polynomial add(Polynomial other) {
            Map<Monomial, Rational> result = new LinkedHashMap<>(terms);
            for (Map.Entry<Monomial, Rational> entry : other.terms.entrySet()) {
                result.merge(entry.getKey(), entry.getValue(), Rational::add);
                if (result.size() > MAX_EXPANDED_TERMS) {
                    return null;
                }
            }
            return new Polynomial(result);
        }

        private Polynomial multiply(Polynomial other) {
            Map<Monomial, Rational> result = new LinkedHashMap<>();
            for (Map.Entry<Monomial, Rational> left : terms.entrySet()) {
                for (Map.Entry<Monomial, Rational> right : other.terms.entrySet()) {
                    Monomial monomial = left.getKey().multiply(right.getKey());
                    if (monomial == null) {
                        return null;
                    }
                    Rational coefficient = left.getValue().multiply(right.getValue());
                    result.merge(monomial, coefficient, Rational::add);
                    if (result.size() > MAX_EXPANDED_TERMS) {
                        return null;
                    }
                }
            }
            return new Polynomial(result);
        }

        private Polynomial scale(long factor) {
            return factor == 1 ? this : multiply(constant(factor));
        }

        private Polynomial pow(int exponent) {
            Polynomial result = constant(1);
            Polynomial factor = this;
            int remaining = exponent;
            while (remaining > 0) {
                if ((remaining & 1) == 1) {
                    result = result.multiply(factor);
                    if (result == null) {
                        return null;
                    }
                }
                remaining >>= 1;
                if (remaining > 0) {
                    factor = factor.multiply(factor);
                    if (factor == null) {
                        return null;
                    }
                }
            }
            return result;
        }

        private Expr toExpr() {
            if (terms.isEmpty()) {
                return new NumberExpr(0);
            }
            Expr result = null;
            for (Map.Entry<Monomial, Rational> entry : orderedTerms()) {
                Rational coefficient = entry.getValue();
                Expr term = withCoefficient(coefficient.abs(), entry.getKey().toExpr());
                if (term == null) {
                    return null;
                }
                if (result == null) {
                    result = coefficient.signum() < 0
                        ? new BinaryExpr(new NumberExpr(0), BinaryOperator.SUB, term)
                        : term;
                } else if (coefficient.signum() < 0) {
                    result = new BinaryExpr(result, BinaryOperator.SUB, term);
                } else {
                    result = new BinaryExpr(result, BinaryOperator.ADD, term);
                }
            }
            return result;
        }

        private List<Map.Entry<Monomial, Rational>> orderedTerms() {
            return terms.entrySet().stream()
                .sorted(TERM_ORDER)
                .toList();
        }

        private static Map<Monomial, Rational> normalizedTerms(Map<Monomial, Rational> source) {
            Map<Monomial, Rational> normalized = new LinkedHashMap<>();
            for (Map.Entry<Monomial, Rational> entry : source.entrySet()) {
                if (!entry.getValue().isZero()) {
                    normalized.put(entry.getKey(), entry.getValue());
                }
            }
            return normalized;
        }
    }

    private static Expr withCoefficient(Rational coefficient, Expr term) {
        if (term instanceof NumberExpr number && number.value() == 1) {
            Double value = coefficient.toFiniteDouble();
            return value == null ? null : new NumberExpr(value);
        }
        if (coefficient.isOne()) {
            return term;
        }
        Double value = coefficient.toFiniteDouble();
        return value == null ? null : new BinaryExpr(new NumberExpr(value), BinaryOperator.MUL, term);
    }

    private static Expr leftAssociate(List<Expr> expressions, BinaryOperator operator) {
        Expr result = expressions.getFirst();
        for (int i = 1; i < expressions.size(); i++) {
            result = new BinaryExpr(result, operator, expressions.get(i));
        }
        return result;
    }
}
