package de.regelsuche.canonical;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.scalar.ExactRationalDomain;
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
 * Safe polynomial normalizer for expressions over numeric constants,
 * variables, addition, subtraction, multiplication and non-negative integer
 * powers.
 *
 * <p>Legacy {@link NumberExpr} nodes still expose {@code double}; conversion
 * of that already-rounded value is isolated in the shared
 * {@link ExactRationalDomain} migration bridge. All normalization arithmetic
 * itself uses the authoritative {@link ExactRational} contract. Exact results
 * are converted back only when the legacy AST can represent the same rational
 * value without rounding.</p>
 */
public final class PolynomialNormalizer {
    private static final int MAX_EXPANDED_TERMS = 1_000;

    private final boolean expandCompositePolynomials;

    public PolynomialNormalizer() {
        this(true);
    }

    private PolynomialNormalizer(boolean expandCompositePolynomials) {
        this.expandCompositePolynomials = expandCompositePolynomials;
    }

    public static PolynomialNormalizer monomialOnly() {
        return new PolynomialNormalizer(false);
    }

    public Optional<Expr> normalize(Expr expression) {
        Polynomial polynomial = toPolynomial(expression);
        if (polynomial == null) {
            return Optional.empty();
        }
        Expr normalized = polynomial.toExpr();
        return normalized == null
            ? Optional.empty()
            : Optional.of(normalized);
    }

    private Polynomial toPolynomial(Expr expression) {
        if (expression instanceof NumberExpr number) {
            return Polynomial.constant(number.value());
        }
        if (expression instanceof VariableExpr variable) {
            return Polynomial.monomial(
                1,
                Monomial.variable(variable.name()));
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

    private Polynomial combine(
        Expr left,
        Expr right,
        int rightSign
    ) {
        Polynomial leftPolynomial = toPolynomial(left);
        Polynomial rightPolynomial = toPolynomial(right);
        if (leftPolynomial == null || rightPolynomial == null) {
            return null;
        }
        return leftPolynomial.add(
            rightPolynomial.scale(rightSign));
    }

    private Polynomial multiply(Expr left, Expr right) {
        Polynomial leftPolynomial = toPolynomial(left);
        Polynomial rightPolynomial = toPolynomial(right);
        if (leftPolynomial == null || rightPolynomial == null) {
            return null;
        }
        if (!expandCompositePolynomials
                && (!leftPolynomial.isMonomial()
                    || !rightPolynomial.isMonomial())) {
            return null;
        }
        return leftPolynomial.multiply(rightPolynomial);
    }

    private Polynomial power(Expr base, Expr exponent) {
        if (!(exponent instanceof NumberExpr number)
                || !isNonNegativeInteger(number.value())) {
            return null;
        }
        int exponentValue = (int) number.value();
        Polynomial basePolynomial = toPolynomial(base);
        if (basePolynomial == null) {
            return null;
        }
        if (!expandCompositePolynomials
                && !basePolynomial.isMonomial()) {
            return null;
        }
        return basePolynomial.pow(exponentValue);
    }

    private boolean isNonNegativeInteger(double value) {
        return value >= 0
            && value <= Integer.MAX_VALUE
            && Math.rint(value) == value;
    }

    private record Monomial(Map<String, Integer> powers) {
        private Monomial {
            powers = Collections.unmodifiableMap(
                new TreeMap<>(powers));
        }

        private static Monomial constant() {
            return new Monomial(Map.of());
        }

        private static Monomial variable(String name) {
            return new Monomial(Map.of(name, 1));
        }

        private Monomial multiply(Monomial other) {
            Map<String, Integer> result = new TreeMap<>(powers);
            for (Map.Entry<String, Integer> entry
                    : other.powers.entrySet()) {
                try {
                    result.merge(
                        entry.getKey(),
                        entry.getValue(),
                        Math::addExact);
                } catch (ArithmeticException exception) {
                    return null;
                }
            }
            result.values().removeIf(value -> value == 0);
            return new Monomial(result);
        }

        private Monomial pow(int exponent) {
            Map<String, Integer> result = new TreeMap<>();
            for (Map.Entry<String, Integer> entry
                    : powers.entrySet()) {
                try {
                    result.put(
                        entry.getKey(),
                        Math.multiplyExact(
                            entry.getValue(),
                            exponent));
                } catch (ArithmeticException exception) {
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
            for (Map.Entry<String, Integer> entry
                    : powers.entrySet()) {
                Expr variable = new VariableExpr(entry.getKey());
                factors.add(entry.getValue() == 1
                    ? variable
                    : new BinaryExpr(
                        variable,
                        BinaryOperator.POW,
                        new NumberExpr(entry.getValue())));
            }
            return leftAssociate(factors, BinaryOperator.MUL);
        }

        private String sortKey() {
            if (powers.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<String, Integer> entry
                    : powers.entrySet()) {
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

    private static final class Polynomial {
        private static final Comparator<
            Map.Entry<Monomial, ExactRational>> TERM_ORDER =
                Comparator
                    .<Map.Entry<Monomial, ExactRational>>comparingInt(
                        entry -> entry.getKey().degree())
                    .reversed()
                    .thenComparing(
                        entry -> entry.getKey().sortKey());

        private final Map<Monomial, ExactRational> terms;

        private Polynomial(Map<Monomial, ExactRational> terms) {
            this.terms = normalizedTerms(terms);
        }

        private static Polynomial constant(double value) {
            ExactRational coefficient = legacyExact(value);
            return coefficient == null
                ? null
                : monomial(
                    coefficient,
                    Monomial.constant());
        }

        private static Polynomial monomial(
            long coefficient,
            Monomial monomial
        ) {
            return monomial(
                ExactRational.integer(coefficient),
                monomial);
        }

        private static Polynomial monomial(
            ExactRational coefficient,
            Monomial monomial
        ) {
            Map<Monomial, ExactRational> result =
                new LinkedHashMap<>();
            result.put(monomial, coefficient);
            return new Polynomial(result);
        }

        private Polynomial add(Polynomial other) {
            Map<Monomial, ExactRational> result =
                new LinkedHashMap<>(terms);
            for (Map.Entry<Monomial, ExactRational> entry
                    : other.terms.entrySet()) {
                result.merge(
                    entry.getKey(),
                    entry.getValue(),
                    ExactRational::add);
                if (result.size() > MAX_EXPANDED_TERMS) {
                    return null;
                }
            }
            return new Polynomial(result);
        }

        private Polynomial multiply(Polynomial other) {
            Map<Monomial, ExactRational> result =
                new LinkedHashMap<>();
            for (Map.Entry<Monomial, ExactRational> left
                    : terms.entrySet()) {
                for (Map.Entry<Monomial, ExactRational> right
                        : other.terms.entrySet()) {
                    Monomial monomial = left.getKey().multiply(
                        right.getKey());
                    if (monomial == null) {
                        return null;
                    }
                    ExactRational coefficient =
                        left.getValue().multiply(right.getValue());
                    result.merge(
                        monomial,
                        coefficient,
                        ExactRational::add);
                    if (result.size() > MAX_EXPANDED_TERMS) {
                        return null;
                    }
                }
            }
            return new Polynomial(result);
        }

        private Polynomial scale(long factor) {
            return factor == 1
                ? this
                : multiply(monomial(
                    factor,
                    Monomial.constant()));
        }

        private boolean isMonomial() {
            return terms.size() <= 1;
        }

        private Polynomial pow(int exponent) {
            Polynomial result = monomial(
                1,
                Monomial.constant());
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
            for (Map.Entry<Monomial, ExactRational> entry
                    : orderedTerms()) {
                ExactRational coefficient = entry.getValue();
                Expr term = withCoefficient(
                    coefficient.abs(),
                    entry.getKey().toExpr());
                if (term == null) {
                    return null;
                }
                if (result == null) {
                    result = coefficient.signum() < 0
                        ? new BinaryExpr(
                            new NumberExpr(0),
                            BinaryOperator.SUB,
                            term)
                        : term;
                } else if (coefficient.signum() < 0) {
                    result = new BinaryExpr(
                        result,
                        BinaryOperator.SUB,
                        term);
                } else {
                    result = new BinaryExpr(
                        result,
                        BinaryOperator.ADD,
                        term);
                }
            }
            return result;
        }

        private List<Map.Entry<Monomial, ExactRational>>
                orderedTerms() {
            return terms.entrySet().stream()
                .sorted(TERM_ORDER)
                .toList();
        }

        private static Map<Monomial, ExactRational> normalizedTerms(
            Map<Monomial, ExactRational> source
        ) {
            Map<Monomial, ExactRational> normalized =
                new LinkedHashMap<>();
            for (Map.Entry<Monomial, ExactRational> entry
                    : source.entrySet()) {
                if (!entry.getValue().isZero()) {
                    normalized.put(
                        entry.getKey(),
                        entry.getValue());
                }
            }
            return normalized;
        }
    }

    /** Temporary convenience for canonical-package callers. */
    static ExactRational legacyExact(double value) {
        return ExactRationalDomain.legacyDecimalValue(value)
            .orElse(null);
    }

    /**
     * Returns an AST expression for exactly the same rational, or {@code null}
     * when the legacy Double-backed AST cannot encode it without rounding.
     */
    static Expr exactRationalExpression(ExactRational value) {
        var legacy = ExactRationalDomain.exactLegacyDecimalDouble(value);
        if (legacy.isPresent()) {
            return new NumberExpr(legacy.getAsDouble());
        }

        NumberExpr numerator = exactIntegerLeaf(value.numerator());
        if (numerator == null) {
            return null;
        }
        if (value.isInteger()) {
            return numerator;
        }
        NumberExpr denominator = exactIntegerLeaf(value.denominator());
        return denominator == null
            ? null
            : new BinaryExpr(
                numerator,
                BinaryOperator.DIV,
                denominator);
    }

    private static NumberExpr exactIntegerLeaf(BigInteger value) {
        var legacy = ExactRationalDomain.exactLegacyDecimalDouble(
            ExactRational.integer(value));
        return legacy.isPresent()
            ? new NumberExpr(legacy.getAsDouble())
            : null;
    }

    private static Expr withCoefficient(
        ExactRational coefficient,
        Expr term
    ) {
        if (term instanceof NumberExpr number
                && number.value() == 1) {
            return exactRationalExpression(coefficient);
        }
        if (coefficient.isOne()) {
            return term;
        }
        Expr exactCoefficient = exactRationalExpression(coefficient);
        return exactCoefficient == null
            ? null
            : new BinaryExpr(
                exactCoefficient,
                BinaryOperator.MUL,
                term);
    }

    private static Expr leftAssociate(
        List<Expr> expressions,
        BinaryOperator operator
    ) {
        Expr result = expressions.getFirst();
        for (int index = 1; index < expressions.size(); index++) {
            result = new BinaryExpr(
                result,
                operator,
                expressions.get(index));
        }
        return result;
    }
}
