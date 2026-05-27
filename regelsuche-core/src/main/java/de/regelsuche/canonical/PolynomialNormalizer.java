package de.regelsuche.canonical;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
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
        return Optional.of(polynomial.toExpr());
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
                result.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
            result.values().removeIf(value -> value == 0);
            return new Monomial(result);
        }

        private Monomial pow(int exponent) {
            Map<String, Integer> result = new TreeMap<>();
            for (Map.Entry<String, Integer> entry : powers.entrySet()) {
                result.put(entry.getKey(), entry.getValue() * exponent);
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

    private static final class Polynomial {
        private static final Comparator<Map.Entry<Monomial, Double>> TERM_ORDER = Comparator
            .<Map.Entry<Monomial, Double>>comparingInt(entry -> entry.getKey().degree())
            .reversed()
            .thenComparing(entry -> entry.getKey().sortKey());

        private final Map<Monomial, Double> terms;

        private Polynomial(Map<Monomial, Double> terms) {
            this.terms = normalizedTerms(terms);
        }

        private static Polynomial constant(double value) {
            return monomial(value, Monomial.constant());
        }

        private static Polynomial monomial(double coefficient, Monomial monomial) {
            Map<Monomial, Double> terms = new LinkedHashMap<>();
            terms.put(monomial, coefficient);
            return new Polynomial(terms);
        }

        private Polynomial add(Polynomial other) {
            Map<Monomial, Double> result = new LinkedHashMap<>(terms);
            for (Map.Entry<Monomial, Double> entry : other.terms.entrySet()) {
                result.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
            return new Polynomial(result);
        }

        private Polynomial scale(double factor) {
            Map<Monomial, Double> result = new LinkedHashMap<>();
            for (Map.Entry<Monomial, Double> entry : terms.entrySet()) {
                result.put(entry.getKey(), entry.getValue() * factor);
            }
            return new Polynomial(result);
        }

        private Polynomial multiply(Polynomial other) {
            Map<Monomial, Double> result = new LinkedHashMap<>();
            for (Map.Entry<Monomial, Double> left : terms.entrySet()) {
                for (Map.Entry<Monomial, Double> right : other.terms.entrySet()) {
                    Monomial monomial = left.getKey().multiply(right.getKey());
                    double coefficient = left.getValue() * right.getValue();
                    result.merge(monomial, coefficient, Double::sum);
                    if (result.size() > MAX_EXPANDED_TERMS) {
                        return null;
                    }
                }
            }
            return new Polynomial(result);
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
            for (Map.Entry<Monomial, Double> entry : orderedTerms()) {
                double coefficient = entry.getValue();
                Expr term = withCoefficient(Math.abs(coefficient), entry.getKey().toExpr());
                if (result == null) {
                    result = coefficient < 0
                        ? new BinaryExpr(new NumberExpr(0), BinaryOperator.SUB, term)
                        : term;
                } else if (coefficient < 0) {
                    result = new BinaryExpr(result, BinaryOperator.SUB, term);
                } else {
                    result = new BinaryExpr(result, BinaryOperator.ADD, term);
                }
            }
            return result;
        }

        private List<Map.Entry<Monomial, Double>> orderedTerms() {
            return terms.entrySet().stream()
                .sorted(TERM_ORDER)
                .toList();
        }

        private static Map<Monomial, Double> normalizedTerms(Map<Monomial, Double> source) {
            Map<Monomial, Double> normalized = new LinkedHashMap<>();
            for (Map.Entry<Monomial, Double> entry : source.entrySet()) {
                if (entry.getValue() != 0) {
                    normalized.put(entry.getKey(), entry.getValue());
                }
            }
            return normalized;
        }
    }

    private static Expr withCoefficient(double coefficient, Expr term) {
        if (term instanceof NumberExpr number && number.value() == 1) {
            return new NumberExpr(coefficient);
        }
        if (coefficient == 1) {
            return term;
        }
        return new BinaryExpr(new NumberExpr(coefficient), BinaryOperator.MUL, term);
    }

    private static Expr leftAssociate(List<Expr> expressions, BinaryOperator operator) {
        Expr result = expressions.getFirst();
        for (int i = 1; i < expressions.size(); i++) {
            result = new BinaryExpr(result, operator, expressions.get(i));
        }
        return result;
    }
}
