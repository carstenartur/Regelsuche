package de.regelsuche.canonical;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
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
 * <p>Numeric leaves and normalization arithmetic use the authoritative
 * {@link ExactRational} contract. Expansion and coefficient bit budgets bound
 * normalization work; accepted coefficients are emitted without rounding.</p>
 */
public final class PolynomialNormalizer implements RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){}

    private static final int MAX_EXPANDED_TERMS = 1_000;
    private static final int MAX_COEFFICIENT_BITS = 4_096;

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
        try(var owned=RetainedOperation.retain(this,expression)) {
            RetainedOperation.work(1);
            Expr existing=normalizedVariablePower(expression);
            if(existing!=null)return RetainedOperation.produced(Optional.of(existing));
            Polynomial polynomial = toPolynomial(expression);
            if (polynomial == null) {
                return Optional.empty();
            }
            try(var value=RetainedOperation.retain(polynomial)) {
                Expr normalized = polynomial.toExpr();
                return normalized == null
                    ? Optional.empty()
                    : RetainedOperation.produced(Optional.of(normalized));
            }
        }
    }

    /** Positive bounded powers of one variable already have the emitted normal form. */
    private Expr normalizedVariablePower(Expr expression){
        RetainedOperation.work(1);
        if(!(expression instanceof BinaryExpr binary) || binary.operator()!=BinaryOperator.POW
                || !(binary.left() instanceof VariableExpr) || !(binary.right() instanceof NumberExpr number))return null;
        RetainedOperation.work(2);
        if(!isNonNegativeInteger(number.value()) || number.value().isZero())return null;
        return number.value().isOne()?binary.left():expression;
    }

    private Polynomial toPolynomial(Expr expression) {
        // normalize owns the complete immutable input throughout this private recursion.
        RetainedOperation.work(1);
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
        if (leftPolynomial == null) return null;
        try(var leftOwned=RetainedOperation.retain(leftPolynomial)) {
            Polynomial rightPolynomial = toPolynomial(right);
            try(var rightOwned=RetainedOperation.retain(rightPolynomial)) {
                if (rightPolynomial == null) {
                    return null;
                }
                var scaled=rightPolynomial.scale(rightSign);
                try(var scaledOwned=RetainedOperation.retain(scaled)){return leftPolynomial.add(scaled);}
            }
        }
    }

    private Polynomial multiply(Expr left, Expr right) {
        Polynomial leftPolynomial = toPolynomial(left);
        if (leftPolynomial == null || (!expandCompositePolynomials && !leftPolynomial.isMonomial())) return null;
        try(var leftOwned=RetainedOperation.retain(leftPolynomial)) {
            Polynomial rightPolynomial = toPolynomial(right);
            try(var rightOwned=RetainedOperation.retain(rightPolynomial)) {
                if (rightPolynomial == null) {
                    return null;
                }
                if (!expandCompositePolynomials && !rightPolynomial.isMonomial()) {
                    return null;
                }
                return leftPolynomial.multiply(rightPolynomial);
            }
        }
    }

    private Polynomial power(Expr base, Expr exponent) {
        if (!(exponent instanceof NumberExpr number)
                || !isNonNegativeInteger(number.value())) {
            return null;
        }
        int exponentValue = number.value().intValueExact();
        // In expression semantics A^0 removes A and is therefore only sound
        // after A is known to be defined and non-zero. Leave this case to the
        // assumption-aware expression canonicalizer instead of folding it as
        // a formal-polynomial identity here.
        if (exponentValue == 0) {
            return null;
        }
        if(base instanceof VariableExpr variable){
            RetainedOperation.work(1);
            return Polynomial.monomial(1,Monomial.variable(variable.name(),exponentValue));
        }
        Polynomial basePolynomial = toPolynomial(base);
        try(var baseOwned=RetainedOperation.retain(basePolynomial)) {
            if (basePolynomial == null) {
                return null;
            }
            if (!expandCompositePolynomials
                    && !basePolynomial.isMonomial()) {
                return null;
            }
            return basePolynomial.pow(exponentValue);
        }
    }

    private boolean isNonNegativeInteger(ExactRational value) {
        return value.isInteger() && value.signum() >= 0
            && value.numerator().bitLength() <= 31;
    }

    private record Monomial(Map<String, Integer> powers) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(powers);}
        // Private factories transfer immutable zero/singleton maps or exclusive natural-order TreeMaps.
        // No caller mutates a transferred powers map; multiply/pow always create distinct accumulators.

        private static Monomial constant() {
            return RetainedOperation.produced(new Monomial(Map.of()));
        }

        private static Monomial variable(String name) {
            return variable(name,1);
        }

        private static Monomial variable(String name,int exponent) {
            var powers = Map.of(name, exponent);
            RetainedOperation.work(2); // Actual singleton map creation and its entry.
            return RetainedOperation.produced(new Monomial(powers));
        }

        private Monomial multiply(Monomial other) {
            Map<String, Integer> result = new TreeMap<>(powers);
            RetainedOperation.work(powers.size());
            RetainedOperation.work(1);
            try(var maps=RetainedOperation.retain(this,other,result)) {
                for (Map.Entry<String, Integer> entry
                        : other.powers.entrySet()) {
                    RetainedOperation.work(1);
                    try {
                        result.merge(
                            entry.getKey(),
                            entry.getValue(),
                            Math::addExact);
                    } catch (ArithmeticException exception) {
                        return null;
                    }
                }
                RetainedOperation.work(result.size());
                result.values().removeIf(value -> value == 0);RetainedOperation.checkpoint();
                return RetainedOperation.produced(new Monomial(result));
            }
        }

        private Monomial pow(int exponent) {
            Map<String, Integer> result = new TreeMap<>();
            RetainedOperation.work(1);
            try(var maps=RetainedOperation.retain(this,result)) {
                for (Map.Entry<String, Integer> entry
                        : powers.entrySet()) {
                    RetainedOperation.work(1);
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
                return RetainedOperation.produced(new Monomial(result));
            }
        }

        private long degree() {
            long degree = 0;
            for (int exponent : powers.values()) {
                degree += exponent;
            }
            return degree;
        }

        private Expr toExpr() {
            if (powers.isEmpty()) {
                return RetainedOperation.produced(new NumberExpr(1));
            }
            RetainedOperation.work(1);
            if (powers.size() == 1) {
                var entry = powers.entrySet().iterator().next();
                RetainedOperation.work(1);
                return variablePower(entry.getKey(), entry.getValue());
            }
            List<Expr> factors = new ArrayList<>();
            RetainedOperation.work(1);
            try (var owned = RetainedOperation.retain(this, factors)) {
                for (Map.Entry<String, Integer> entry : powers.entrySet()) {
                    Expr factor = variablePower(entry.getKey(), entry.getValue());
                    factors.add(factor);
                    RetainedOperation.work(1);
                    RetainedOperation.checkpoint();
                }
                return leftAssociate(factors, BinaryOperator.MUL);
            }
        }

        private static Expr variablePower(String name, int power) {
            Expr variable = new VariableExpr(name);
            RetainedOperation.work(1);
            if (power == 1) return RetainedOperation.produced(variable);
            try (var leaf = RetainedOperation.retain(variable)) {
                var exponent = new NumberExpr(power);
                RetainedOperation.work(1);
                try (var number = RetainedOperation.retain(exponent)) {
                    return RetainedOperation.produced(new BinaryExpr(variable, BinaryOperator.POW, exponent));
                }
            }
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

    private static final class Polynomial implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(terms);}
        private static final Comparator<Monomial> TERM_ORDER =
            Comparator.comparingLong(Monomial::degree).reversed()
                .thenComparing(Monomial::sortKey);

        private final Map<Monomial, ExactRational> terms;

        /** All callers transfer a fresh private accumulator after their existing term-limit checks. */
        private Polynomial(LinkedHashMap<Monomial, ExactRational> terms) {
            this.terms = terms;
            removeZeroTerms();
        }

        private static Polynomial constant(ExactRational value) {
            return monomial(value, Monomial.constant());
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
            LinkedHashMap<Monomial, ExactRational> result =
                new LinkedHashMap<>();
            RetainedOperation.work(1);
            try(var maps=RetainedOperation.retain(monomial,coefficient,result)) {
                result.put(monomial, coefficient);RetainedOperation.work(1);
                return RetainedOperation.produced(new Polynomial(result));
            }
        }

        private Polynomial add(Polynomial other) {
            LinkedHashMap<Monomial, ExactRational> result =
                new LinkedHashMap<>(terms);
            RetainedOperation.work(terms.size());
            RetainedOperation.work(1);
            try(var maps=RetainedOperation.retain(this,other,result)) {
                for (Map.Entry<Monomial, ExactRational> entry
                        : other.terms.entrySet()) {
                    RetainedOperation.work(1);
                    result.merge(
                        entry.getKey(),
                        entry.getValue(),
                        ExactRational::add);
                    RetainedOperation.checkpoint();
                    if (result.size() > MAX_EXPANDED_TERMS) {
                        return null;
                    }
                }
                return RetainedOperation.produced(new Polynomial(result));
            }
        }

        private Polynomial multiply(Polynomial other) {
            LinkedHashMap<Monomial, ExactRational> result =
                new LinkedHashMap<>();
            RetainedOperation.work(1);
            try(var maps=RetainedOperation.retain(this,other,result)) {
                for (Map.Entry<Monomial, ExactRational> left
                        : terms.entrySet()) {
                    for (Map.Entry<Monomial, ExactRational> right
                            : other.terms.entrySet()) {
                        Monomial monomial = left.getKey().multiply(
                            right.getKey());
                        try(var monomialOwned=RetainedOperation.retain(monomial)) {
                            if (monomial == null) {
                                return null;
                            }
                            ExactRational coefficient =
                                left.getValue().multiply(right.getValue());
                            RetainedOperation.work(1);
                            try(var coefficientOwned=RetainedOperation.retain(coefficient)) {
                                if (!withinCoefficientBudget(coefficient)) {
                                    return null;
                                }
                                result.merge(
                                    monomial,
                                    coefficient,
                                    ExactRational::add);
                                RetainedOperation.work(1);RetainedOperation.checkpoint();
                                if (result.size() > MAX_EXPANDED_TERMS) {
                                    return null;
                                }
                            }
                        }
                    }
                }
                return RetainedOperation.produced(new Polynomial(result));
            }
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
            Object[] current={result,factor};
            try(var state=RetainedOperation.retain(this,current)) {
                int remaining = exponent;
                while (remaining > 0) {
                    if ((remaining & 1) == 1) {
                        result = result.multiply(factor);current[0]=result;RetainedOperation.work(1);RetainedOperation.checkpoint();
                        if (result == null) {
                            return null;
                        }
                    }
                    remaining >>= 1;
                    if (remaining > 0) {
                        factor = factor.multiply(factor);current[1]=factor;RetainedOperation.work(1);RetainedOperation.checkpoint();
                        if (factor == null) {
                            return null;
                        }
                    }
                }
                return RetainedOperation.produced(result);
            }
        }

        private static boolean withinCoefficientBudget(ExactRational value) {
            return value.numerator().abs().bitLength() <= MAX_COEFFICIENT_BITS
                && value.denominator().bitLength() <= MAX_COEFFICIENT_BITS;
        }

        private Expr toExpr() {
            if (terms.isEmpty()) {
                return RetainedOperation.produced(new NumberExpr(0));
            }
            List<Monomial> ordered = orderedTerms();
            Object[] current = {null};
            RetainedOperation.work(1);
            try (var owned = RetainedOperation.retain(this, ordered, current)) {
                for (Monomial monomial : ordered) {
                    ExactRational coefficient = terms.get(monomial);
                    ExactRational magnitude = coefficient.abs();
                    RetainedOperation.work(2);
                    try (var scalar = RetainedOperation.retain(magnitude)) {
                        Expr base = monomial.toExpr();
                        try (var renderedBase = RetainedOperation.retain(base)) {
                            Expr term = withCoefficient(magnitude, base);
                            try (var renderedTerm = RetainedOperation.retain(term)) {
                                if (term == null) return null;
                                Expr result = (Expr) current[0];
                                if (result == null) {
                                    if (coefficient.signum() < 0) {
                                        var zero = new NumberExpr(0);
                                        RetainedOperation.work(1);
                                        try (var leaf = RetainedOperation.retain(zero)) {
                                            result = RetainedOperation.produced(new BinaryExpr(zero, BinaryOperator.SUB, term));
                                        }
                                    } else result = term;
                                } else {
                                    result = RetainedOperation.produced(new BinaryExpr(result,
                                        coefficient.signum() < 0 ? BinaryOperator.SUB : BinaryOperator.ADD, term));
                                }
                                current[0] = result;
                                RetainedOperation.work(1);
                                RetainedOperation.checkpoint();
                            }
                        }
                    }
                }
                return RetainedOperation.produced((Expr) current[0]);
            }
        }

        private List<Monomial> orderedTerms() {
            // Same stable term order, with owned keys instead of opaque backing Map.Entry views.
            var ordered = new ArrayList<>(terms.keySet());
            RetainedOperation.work(terms.size() + 1L);
            try (var owned = RetainedOperation.retain(this, ordered)) {
                ordered.sort((left, right) -> {
                    RetainedOperation.work(1);
                    return TERM_ORDER.compare(left, right);
                });
                return RetainedOperation.produced(ordered);
            }
        }

        private void removeZeroTerms() {
            var entries = terms.entrySet().iterator();
            boolean observedBeforeRemoval = false;
            while (entries.hasNext()) {
                var entry = entries.next();
                RetainedOperation.work(1);
                if (entry.getValue().isZero()) {
                    if (!observedBeforeRemoval) {
                        // The filled accumulator and new owner are visible before any entries disappear.
                        RetainedOperation.produced(this);
                        observedBeforeRemoval = true;
                    }
                    entries.remove();
                    RetainedOperation.work(1);
                }
            }
        }

    }

    static Expr exactRationalExpression(ExactRational value) {
        return RetainedOperation.produced(new NumberExpr(value));
    }

    private static Expr withCoefficient(ExactRational coefficient, Expr term) {
        RetainedOperation.work(1);
        if (coefficient.isOne()) return term;
        try (var operands = RetainedOperation.retain(coefficient, term)) {
            if (term instanceof NumberExpr number && number.value().equalsInteger(1)) {
                return exactRationalExpression(coefficient);
            }
            Expr exactCoefficient = exactRationalExpression(coefficient);
            try (var leaf = RetainedOperation.retain(exactCoefficient)) {
                return exactCoefficient == null ? null
                    : RetainedOperation.produced(new BinaryExpr(exactCoefficient, BinaryOperator.MUL, term));
            }
        }
    }

    private static Expr leftAssociate(List<Expr> expressions, BinaryOperator operator) {
        RetainedOperation.work(1);
        if (expressions.size() == 1) return expressions.getFirst();
        Object[] current = {expressions.getFirst()};
        RetainedOperation.work(1);
        try (var owned = RetainedOperation.retain(expressions, current)) {
            for (int index = 1; index < expressions.size(); index++) {
                current[0] = new BinaryExpr((Expr) current[0], operator, expressions.get(index));
                RetainedOperation.work(2);
                RetainedOperation.checkpoint();
            }
            return RetainedOperation.produced((Expr) current[0]);
        }
    }
}
