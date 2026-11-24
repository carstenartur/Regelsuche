package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.LongConsumer;

/** Bounded structural proposals for exact rational polynomials. The receiver checks each endpoint. */
public final class SparsePolynomialHorner {
    private static final int MAX_TERMS = 64;
    private static final int MAX_COEFFICIENT_BITS = 256;
    private static final int MAX_OPERATIONS = 120;
    private static final NumberExpr ZERO = new NumberExpr(0);

    private SparsePolynomialHorner() { }

    public static Optional<Expr> propose(Expr source, LongConsumer work) {
        Polynomial polynomial = new ExactResidualPolynomialArithmetic(work, true).project(source);
        var variables = polynomial.variables().stream().sorted().toList();
        var terms = polynomial.terms();
        if (variables.size() > ExactPolynomialAnalysis.MAX_VARIABLES || terms.size() > MAX_TERMS) {
            return Optional.empty();
        }
        BigInteger commonDenominator = BigInteger.ONE;
        for (Rational coefficient : terms.values()) {
            work.accept(1);
            if (!fits(coefficient.numerator()) || !fits(coefficient.denominator())) return Optional.empty();
            if (fits(commonDenominator)) {
                BigInteger denominator = coefficient.denominator();
                commonDenominator = commonDenominator.divide(commonDenominator.gcd(denominator)).multiply(denominator);
            }
        }

        var best = new Best();
        search(variables, new ArrayList<>(), terms, BigInteger.ONE, work, best);
        if (!commonDenominator.equals(BigInteger.ONE) && fits(commonDenominator)) {
            search(variables, new ArrayList<>(), terms, commonDenominator, work, best);
        }
        return Optional.ofNullable(best.expression);
    }

    private static void search(List<String> variables, List<String> order, Map<Monomial, Rational> terms,
            BigInteger scale, LongConsumer work, Best best) {
        if (order.size() == variables.size()) {
            work.accept(1);
            Optional<Part> candidate = horner(new ArrayList<>(terms.entrySet()), order, 0, scale, work);
            if (candidate.isEmpty()) return;
            Part part = candidate.orElseThrow();
            if (!scale.equals(BigInteger.ONE)) {
                part = new Part(binary(part.magnitude(), BinaryOperator.DIV, integer(scale), work),
                    part.operations() + 1, part.negative());
            }
            int operations = part.operations() + (part.negative() ? 1 : 0);
            if (operations < best.operations) {
                best.operations = operations;
                best.expression = part.negative() ? binary(ZERO, BinaryOperator.SUB, part.magnitude(), work) : part.magnitude();
            }
            return;
        }
        for (String variable : variables) {
            work.accept(1);
            if (!order.contains(variable)) {
                order.add(variable);
                search(variables, order, terms, scale, work, best);
                order.removeLast();
            }
        }
    }

    private static Optional<Part> horner(List<Map.Entry<Monomial, Rational>> terms, List<String> order,
            int level, BigInteger scale, LongConsumer work) {
        work.accept(1);
        if (terms.isEmpty()) return Optional.of(new Part(ZERO, 0, false));
        if (level == order.size()) return coefficient(terms.getFirst().getValue(), scale, work);

        String variable = order.get(level);
        var groups = new TreeMap<Integer, List<Map.Entry<Monomial, Rational>>>();
        for (var term : terms) {
            work.accept(1);
            int exponent = term.getKey().powers().getOrDefault(variable, 0);
            groups.computeIfAbsent(exponent, ignored -> new ArrayList<>()).add(term);
        }

        Part result = null;
        int previous = groups.firstKey();
        for (var group : groups.descendingMap().entrySet()) {
            int exponent = group.getKey();
            var coefficient = horner(group.getValue(), order, level + 1, scale, work);
            if (coefficient.isEmpty()) return Optional.empty();
            Part term = coefficient.orElseThrow();
            result = result == null ? term : add(shift(result, variable, previous - exponent, work), term, work);
            if (result.operations() > MAX_OPERATIONS) return Optional.empty();
            previous = exponent;
        }
        return Optional.of(shift(result, variable, previous, work));
    }

    private static Optional<Part> coefficient(Rational coefficient, BigInteger scale, LongConsumer work) {
        BigInteger denominator = coefficient.denominator();
        BigInteger numerator = coefficient.numerator();
        if (!scale.equals(BigInteger.ONE)) {
            numerator = numerator.multiply(scale.divide(denominator));
            if (!fits(numerator)) return Optional.empty();
            denominator = BigInteger.ONE;
        }
        NumberExpr magnitude = integer(numerator.abs());
        return Optional.of(denominator.equals(BigInteger.ONE) ? new Part(magnitude, 0, numerator.signum() < 0)
            : new Part(binary(magnitude, BinaryOperator.DIV, integer(denominator), work), 1, numerator.signum() < 0));
    }

    private static Part shift(Part value, String variable, int exponent, LongConsumer work) {
        if (exponent == 0) return value;
        Expr factor = new VariableExpr(variable);
        Expr result = value.magnitude();
        int operations = value.operations();
        if (result instanceof NumberExpr number && number.value().equals(ExactRational.ONE)) {
            result = factor;
            exponent--;
        }
        while (exponent-- > 0) {
            result = binary(factor, BinaryOperator.MUL, result, work);
            operations++;
        }
        return new Part(result, operations, value.negative());
    }

    private static Part add(Part left, Part right, LongConsumer work) {
        int operations = left.operations() + right.operations() + 1;
        if (left.negative() == right.negative()) {
            return new Part(binary(left.magnitude(), BinaryOperator.ADD, right.magnitude(), work), operations, left.negative());
        }
        Part positive = left.negative() ? right : left;
        Part negative = left.negative() ? left : right;
        return new Part(binary(positive.magnitude(), BinaryOperator.SUB, negative.magnitude(), work), operations, false);
    }

    private static BinaryExpr binary(Expr left, BinaryOperator operator, Expr right, LongConsumer work) {
        work.accept(1);
        return new BinaryExpr(left, operator, right);
    }

    private static NumberExpr integer(BigInteger value) {
        return new NumberExpr(new ExactRational(value, BigInteger.ONE));
    }

    private static boolean fits(BigInteger integer) { return integer.abs().bitLength() <= MAX_COEFFICIENT_BITS; }

    private record Part(Expr magnitude, int operations, boolean negative) { }

    private static final class Best {
        private int operations = MAX_OPERATIONS + 1;
        private Expr expression;
    }
}
