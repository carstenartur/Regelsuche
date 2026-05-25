package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

final class PolynomialArithmetic {
    static final MathContext MC = MathContext.DECIMAL128;

    private final ExpressionParser parser = new ExpressionParser();

    Optional<Polynomial> parse(String expression) {
        try {
            Expr expr = parser.parseTerm(expression);
            return asPolynomial(expr);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private Optional<Polynomial> asPolynomial(Expr expression) {
        if (expression instanceof NumberExpr numberExpr) {
            return Optional.of(Polynomial.constant(BigDecimal.valueOf(numberExpr.value())));
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

    record LinearEquation(BigDecimal coefficient, Polynomial rest) {
    }

    record Term(Monomial monomial, BigDecimal coefficient) {
    }

    record Monomial(Map<String, Integer> powers) {
        Monomial {
            powers = Map.copyOf(powers);
        }

        static Monomial constant() {
            return new Monomial(Map.of());
        }

        static Monomial variable(String variable) {
            return new Monomial(Map.of(variable, 1));
        }

        Monomial multiply(Monomial other) {
            Map<String, Integer> merged = new HashMap<>(powers);
            for (Map.Entry<String, Integer> entry : other.powers.entrySet()) {
                merged.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
            merged.entrySet().removeIf(entry -> entry.getValue() == 0);
            return new Monomial(merged);
        }

        boolean divides(Monomial other) {
            return powers.entrySet().stream()
                .allMatch(entry -> other.powers.getOrDefault(entry.getKey(), 0) >= entry.getValue());
        }

        Monomial divideBy(Monomial divisor) {
            Map<String, Integer> quotient = new HashMap<>(powers);
            divisor.powers.forEach((variable, exponent) ->
                quotient.compute(variable, (ignored, current) -> current == null ? -exponent : current - exponent));
            quotient.entrySet().removeIf(entry -> entry.getValue() == 0);
            return new Monomial(quotient);
        }

        Monomial lcm(Monomial other) {
            Map<String, Integer> merged = new HashMap<>(powers);
            other.powers.forEach((variable, exponent) ->
                merged.merge(variable, exponent, Math::max));
            return new Monomial(merged);
        }

        int exponentOf(String variable) {
            return powers.getOrDefault(variable, 0);
        }

        Monomial without(String variable) {
            if (!powers.containsKey(variable)) {
                return this;
            }
            Map<String, Integer> reduced = new HashMap<>(powers);
            reduced.remove(variable);
            return new Monomial(reduced);
        }

        int totalDegree() {
            return powers.values().stream().mapToInt(Integer::intValue).sum();
        }

        String key() {
            TreeMap<String, Integer> ordered = new TreeMap<>(powers);
            StringBuilder builder = new StringBuilder();
            ordered.forEach((name, exponent) -> {
                if (builder.length() > 0) {
                    builder.append('*');
                }
                builder.append(name);
                if (exponent != 1) {
                    builder.append('^').append(exponent);
                }
            });
            return builder.toString();
        }
    }

    static final class Polynomial {
        private static final Comparator<Monomial> MONOMIAL_ORDER = Comparator
            .comparingInt(Monomial::totalDegree)
            .reversed()
            .thenComparing(Monomial::key);

        private final Map<Monomial, BigDecimal> terms;

        private Polynomial(Map<Monomial, BigDecimal> terms) {
            this.terms = new HashMap<>();
            terms.forEach(this::addTerm);
        }

        static Polynomial constant(BigDecimal value) {
            return new Polynomial(Map.of(Monomial.constant(), value));
        }

        static Polynomial variable(String variable) {
            return new Polynomial(Map.of(Monomial.variable(variable), BigDecimal.ONE));
        }

        static Polynomial term(Monomial monomial, BigDecimal coefficient) {
            return new Polynomial(Map.of(monomial, coefficient));
        }

        boolean isZero() {
            return terms.isEmpty();
        }

        Optional<Term> leadingTerm() {
            return terms.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(MONOMIAL_ORDER))
                .findFirst()
                .map(entry -> new Term(entry.getKey(), entry.getValue()));
        }

        Polynomial add(Polynomial other) {
            Map<Monomial, BigDecimal> merged = new HashMap<>(terms);
            other.terms.forEach((monomial, coefficient) -> merged.merge(monomial, coefficient, BigDecimal::add));
            return new Polynomial(merged);
        }

        Polynomial subtract(Polynomial other) {
            return add(other.multiply(BigDecimal.ONE.negate()));
        }

        Polynomial multiply(BigDecimal scalar) {
            Map<Monomial, BigDecimal> scaled = new HashMap<>();
            terms.forEach((monomial, coefficient) -> scaled.put(monomial, coefficient.multiply(scalar, MC)));
            return new Polynomial(scaled);
        }

        Polynomial multiply(Monomial monomial, BigDecimal coefficient) {
            return multiply(term(monomial, coefficient));
        }

        Polynomial multiply(Polynomial other) {
            Map<Monomial, BigDecimal> multiplied = new HashMap<>();
            for (Map.Entry<Monomial, BigDecimal> left : terms.entrySet()) {
                for (Map.Entry<Monomial, BigDecimal> right : other.terms.entrySet()) {
                    Monomial monomial = left.getKey().multiply(right.getKey());
                    BigDecimal coefficient = left.getValue().multiply(right.getValue(), MC);
                    multiplied.merge(monomial, coefficient, BigDecimal::add);
                }
            }
            return new Polynomial(multiplied);
        }

        Polynomial pow(int exponent) {
            Polynomial result = constant(BigDecimal.ONE);
            for (int i = 0; i < exponent; i++) {
                result = result.multiply(this);
            }
            return result;
        }

        Optional<LinearEquation> isolateLinear(String variable) {
            BigDecimal variableCoefficient = BigDecimal.ZERO;
            Map<Monomial, BigDecimal> restTerms = new HashMap<>();
            for (Map.Entry<Monomial, BigDecimal> entry : terms.entrySet()) {
                Monomial monomial = entry.getKey();
                int exponent = monomial.exponentOf(variable);
                if (exponent == 0) {
                    restTerms.put(monomial, entry.getValue());
                    continue;
                }
                if (exponent != 1 || monomial.without(variable).totalDegree() != 0) {
                    return Optional.empty();
                }
                variableCoefficient = variableCoefficient.add(entry.getValue(), MC);
            }
            if (variableCoefficient.compareTo(BigDecimal.ZERO) == 0) {
                return Optional.empty();
            }
            return Optional.of(new LinearEquation(variableCoefficient, new Polynomial(restTerms)));
        }

        private void addTerm(Monomial monomial, BigDecimal coefficient) {
            if (coefficient.compareTo(BigDecimal.ZERO) == 0) {
                return;
            }
            BigDecimal merged = terms.getOrDefault(monomial, BigDecimal.ZERO).add(coefficient, MC);
            if (merged.compareTo(BigDecimal.ZERO) == 0) {
                terms.remove(monomial);
            } else {
                terms.put(monomial, merged.stripTrailingZeros());
            }
        }

        String toCanonicalString() {
            if (terms.isEmpty()) {
                return "0";
            }
            List<Map.Entry<Monomial, BigDecimal>> ordered = terms.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(MONOMIAL_ORDER))
                .toList();
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<Monomial, BigDecimal> entry : ordered) {
                BigDecimal coefficient = entry.getValue();
                Monomial monomial = entry.getKey();
                boolean negative = coefficient.compareTo(BigDecimal.ZERO) < 0;
                BigDecimal absolute = coefficient.abs();
                String monomialKey = monomial.key();

                if (builder.length() > 0) {
                    builder.append(negative ? " - " : " + ");
                } else if (negative) {
                    builder.append('-');
                }

                boolean writeCoefficient = monomialKey.isEmpty() || absolute.compareTo(BigDecimal.ONE) != 0;
                if (writeCoefficient) {
                    builder.append(absolute.stripTrailingZeros().toPlainString());
                }
                if (!monomialKey.isEmpty()) {
                    if (writeCoefficient) {
                        builder.append('*');
                    }
                    builder.append(monomialKey);
                }
            }
            return builder.toString();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Polynomial polynomial)) {
                return false;
            }
            return terms.equals(polynomial.terms);
        }

        @Override
        public int hashCode() {
            return terms.hashCode();
        }
    }
}
