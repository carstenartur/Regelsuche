package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Converts a bounded expression fragment into an exact commutative polynomial
 * over structural AST atoms.
 *
 * <p>An atom is not limited to a variable name. A complete subtree such as
 * {@code x + 1} or {@code sin(t)} can be one indeterminate. Consequently one
 * semantic polynomial covers infinitely many concrete substitutions without
 * learning a separate rule for every substituted expression.</p>
 */
public final class PolynomialSemanticView {
    public static final String VIEW_ID =
        "regelsuche.polynomial-semantic-view/v1";

    private final ExpressionParser parser = new ExpressionParser();
    private final Budget budget;

    public PolynomialSemanticView() {
        this(Budget.DEFAULT);
    }

    public PolynomialSemanticView(Budget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public Analysis analyze(String expression) {
        if (expression == null || expression.isBlank()) {
            return Analysis.failure(Status.PARSE_ERROR, "EXPRESSION_BLANK");
        }
        Work work = new Work();
        try {
            Expr root = parser.parse(new InputRequest(InputType.TERM, expression))
                .terms().getFirst();
            List<RawTerm> rawTerms = new ArrayList<>();
            collectAddends(root, BigInteger.ONE, rawTerms, work);
            if (rawTerms.size() > budget.maxTerms()) {
                throw new BudgetExceeded("MAX_TERMS_EXCEEDED");
            }

            List<RawTerm> effectiveTerms = rawTerms.stream()
                .filter(term -> term.coefficient().signum() != 0)
                .toList();
            Map<String, Expr> atomExpressions = new LinkedHashMap<>();
            effectiveTerms.forEach(term -> term.atoms().forEach((key, atom) ->
                atomExpressions.putIfAbsent(key, atom.expression())));
            List<String> atomKeys = atomExpressions.keySet().stream()
                .sorted()
                .toList();
            if (atomKeys.size() > budget.maxAtoms()) {
                throw new BudgetExceeded("MAX_ATOMS_EXCEEDED");
            }

            List<Atom> atoms = atomKeys.stream()
                .map(key -> new Atom(
                    key,
                    ExpressionFormatter.format(atomExpressions.get(key)),
                    atomExpressions.get(key)))
                .toList();
            Map<String, Integer> atomIndexes = new LinkedHashMap<>();
            for (int index = 0; index < atomKeys.size(); index++) {
                atomIndexes.put(atomKeys.get(index), index);
            }

            TreeMap<Monomial, BigInteger> coefficients = new TreeMap<>();
            for (RawTerm raw : effectiveTerms) {
                List<Integer> exponents = new ArrayList<>(
                    Collections.nCopies(atomKeys.size(), 0));
                raw.atoms().forEach((key, atom) -> {
                    int index = atomIndexes.get(key);
                    exponents.set(index, atom.exponent());
                });
                Monomial monomial = new Monomial(exponents);
                if (monomial.totalDegree() > budget.maxDegree()) {
                    throw new BudgetExceeded("MAX_DEGREE_EXCEEDED");
                }
                coefficients.merge(
                    monomial,
                    raw.coefficient(),
                    BigInteger::add);
            }
            coefficients.entrySet().removeIf(entry ->
                entry.getValue().signum() == 0);

            int degree = coefficients.keySet().stream()
                .mapToInt(Monomial::totalDegree)
                .max()
                .orElse(0);
            boolean homogeneous = coefficients.isEmpty()
                || coefficients.keySet().stream()
                    .mapToInt(Monomial::totalDegree)
                    .distinct()
                    .count() == 1;
            Polynomial polynomial = new Polynomial(
                VIEW_ID,
                atoms,
                coefficients,
                degree,
                homogeneous,
                work.visitedNodes());
            return new Analysis(
                Status.SUPPORTED,
                "EXACT_INTEGER_POLYNOMIAL",
                polynomial);
        } catch (BudgetExceeded exception) {
            return Analysis.failure(Status.BUDGET_EXCEEDED, exception.getMessage());
        } catch (UnsupportedExpression exception) {
            return Analysis.failure(Status.UNSUPPORTED, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return Analysis.failure(Status.PARSE_ERROR, safeMessage(exception));
        }
    }

    private void collectAddends(
        Expr expression,
        BigInteger sign,
        List<RawTerm> terms,
        Work work
    ) {
        work.visit(budget);
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.ADD) {
            collectAddends(binary.left(), sign, terms, work);
            collectAddends(binary.right(), sign, terms, work);
            return;
        }
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.SUB) {
            collectAddends(binary.left(), sign, terms, work);
            collectAddends(binary.right(), sign.negate(), terms, work);
            return;
        }
        MutableTerm term = new MutableTerm(sign);
        collectProduct(expression, term, work);
        terms.add(term.freeze());
    }

    private void collectProduct(
        Expr expression,
        MutableTerm term,
        Work work
    ) {
        work.visit(budget);
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.MUL) {
            collectProduct(binary.left(), term, work);
            collectProduct(binary.right(), term, work);
            return;
        }
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.DIV) {
            throw unsupported("DIVISION_NOT_IN_INTEGER_POLYNOMIAL_VIEW");
        }
        if (expression instanceof NumberExpr number) {
            term.multiply(exactInteger(number.value()));
            return;
        }
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.POW) {
            int exponent = exactExponent(binary.right());
            if (binary.left() instanceof NumberExpr number) {
                term.multiply(exactInteger(number.value()).pow(exponent));
                return;
            }
            if (exponent > budget.maxDegree()) {
                throw new BudgetExceeded("MAX_DEGREE_EXCEEDED");
            }
            if (exponent > 0) {
                term.addAtom(binary.left(), exponent);
            }
            return;
        }
        term.addAtom(expression, 1);
    }

    private int exactExponent(Expr expression) {
        if (!(expression instanceof NumberExpr number)) {
            throw unsupported("POWER_EXPONENT_MUST_BE_NONNEGATIVE_INTEGER");
        }
        BigInteger integer = exactInteger(number.value());
        if (integer.signum() < 0
                || integer.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw unsupported("POWER_EXPONENT_OUTSIDE_SUPPORTED_RANGE");
        }
        return integer.intValueExact();
    }

    private static BigInteger exactInteger(double value) {
        if (!Double.isFinite(value) || value != Math.rint(value)) {
            throw unsupported("COEFFICIENT_MUST_BE_EXACT_INTEGER");
        }
        try {
            return BigDecimal.valueOf(value).toBigIntegerExact();
        } catch (ArithmeticException exception) {
            throw unsupported("COEFFICIENT_OUTSIDE_EXACT_INTEGER_RANGE");
        }
    }

    private static String atomKey(Expr expression) {
        return ExpressionFormatter.format(expression);
    }

    private static UnsupportedExpression unsupported(String detailCode) {
        return new UnsupportedExpression(detailCode);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
            ? exception.getClass().getSimpleName()
            : message;
    }

    public enum Status {
        SUPPORTED,
        PARSE_ERROR,
        UNSUPPORTED,
        BUDGET_EXCEEDED
    }

    public record Budget(
        int maxAtoms,
        int maxDegree,
        int maxTerms,
        int maxVisitedNodes
    ) {
        public static final Budget DEFAULT = new Budget(4, 12, 64, 512);

        public Budget {
            if (maxAtoms < 1 || maxDegree < 0
                    || maxTerms < 1 || maxVisitedNodes < 1) {
                throw new IllegalArgumentException(
                    "polynomial semantic-view budget is invalid");
            }
        }
    }

    public record Analysis(
        Status status,
        String detailCode,
        Polynomial polynomial
    ) {
        public Analysis {
            Objects.requireNonNull(status, "status");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "detailCode must not be blank");
            }
            if (status == Status.SUPPORTED && polynomial == null) {
                throw new IllegalArgumentException(
                    "supported analysis requires a polynomial");
            }
            if (status != Status.SUPPORTED && polynomial != null) {
                throw new IllegalArgumentException(
                    "failed analysis must not expose a polynomial");
            }
        }

        static Analysis failure(Status status, String detailCode) {
            return new Analysis(status, detailCode, null);
        }

        public boolean supported() {
            return status == Status.SUPPORTED;
        }
    }

    public record Atom(
        String key,
        String display,
        Expr expression
    ) {
        public Atom {
            if (key == null || key.isBlank()
                    || display == null || display.isBlank()
                    || expression == null) {
                throw new IllegalArgumentException(
                    "polynomial atom is invalid");
            }
        }
    }

    public record Monomial(List<Integer> exponents)
            implements Comparable<Monomial> {
        public Monomial {
            exponents = List.copyOf(exponents);
            if (exponents.stream().anyMatch(value -> value == null || value < 0)) {
                throw new IllegalArgumentException(
                    "monomial exponents must be nonnegative");
            }
        }

        public int totalDegree() {
            return exponents.stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public int compareTo(Monomial other) {
            int degreeComparison = Integer.compare(
                other.totalDegree(),
                totalDegree());
            if (degreeComparison != 0) {
                return degreeComparison;
            }
            int length = Math.max(exponents.size(), other.exponents.size());
            for (int index = 0; index < length; index++) {
                int left = index < exponents.size() ? exponents.get(index) : 0;
                int right = index < other.exponents.size()
                    ? other.exponents.get(index)
                    : 0;
                int comparison = Integer.compare(right, left);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return Integer.compare(exponents.size(), other.exponents.size());
        }

        public String canonicalMaterial() {
            return exponents.stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        }
    }

    public record Polynomial(
        String viewId,
        List<Atom> atoms,
        Map<Monomial, BigInteger> coefficients,
        int degree,
        boolean homogeneous,
        int visitedNodes
    ) {
        public Polynomial {
            if (!VIEW_ID.equals(viewId)
                    || degree < 0
                    || visitedNodes < 0) {
                throw new IllegalArgumentException(
                    "polynomial semantic view is invalid");
            }
            atoms = List.copyOf(atoms);
            int atomCount = atoms.size();
            TreeMap<Monomial, BigInteger> sorted = new TreeMap<>();
            coefficients.forEach((monomial, coefficient) -> {
                if (coefficient.signum() != 0) {
                    sorted.put(monomial, coefficient);
                }
            });
            coefficients = Collections.unmodifiableMap(sorted);
            if (coefficients.keySet().stream().anyMatch(monomial ->
                    monomial.exponents().size() != atomCount)) {
                throw new IllegalArgumentException(
                    "monomial arity must equal atom count");
            }
        }

        public BigInteger coefficient(int... exponents) {
            List<Integer> values = java.util.Arrays.stream(exponents)
                .boxed()
                .toList();
            if (values.size() != atoms.size()) {
                throw new IllegalArgumentException(
                    "coefficient query arity must equal atom count");
            }
            return coefficients.getOrDefault(
                new Monomial(values),
                BigInteger.ZERO);
        }

        public boolean isHomogeneousOfDegree(int expectedDegree) {
            return homogeneous && degree == expectedDegree;
        }

        public String canonicalMaterial() {
            StringBuilder result = new StringBuilder(viewId);
            atoms.forEach(atom -> append(result, atom.display()));
            coefficients.forEach((monomial, coefficient) -> {
                append(result, monomial.canonicalMaterial());
                append(result, coefficient.toString());
            });
            return result.toString();
        }

        private static void append(StringBuilder target, String value) {
            target.append('|').append(value.length()).append(':').append(value);
        }
    }

    private static final class MutableTerm {
        private BigInteger coefficient;
        private final Map<String, MutableAtom> atoms = new LinkedHashMap<>();

        private MutableTerm(BigInteger coefficient) {
            this.coefficient = coefficient;
        }

        private void multiply(BigInteger value) {
            coefficient = coefficient.multiply(value);
        }

        private void addAtom(Expr expression, int exponent) {
            String key = atomKey(expression);
            atoms.compute(key, (ignored, current) -> current == null
                ? new MutableAtom(expression, exponent)
                : new MutableAtom(
                    current.expression(),
                    Math.addExact(current.exponent(), exponent)));
        }

        private RawTerm freeze() {
            Map<String, RawAtom> frozen = new LinkedHashMap<>();
            atoms.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> frozen.put(
                    entry.getKey(),
                    new RawAtom(
                        entry.getValue().expression(),
                        entry.getValue().exponent())));
            return new RawTerm(
                coefficient,
                Collections.unmodifiableMap(frozen));
        }
    }

    private record MutableAtom(Expr expression, int exponent) {
    }

    private record RawAtom(Expr expression, int exponent) {
    }

    private record RawTerm(
        BigInteger coefficient,
        Map<String, RawAtom> atoms
    ) {
    }

    private static final class Work {
        private int visitedNodes;

        private void visit(Budget budget) {
            visitedNodes++;
            if (visitedNodes > budget.maxVisitedNodes()) {
                throw new BudgetExceeded("MAX_VISITED_NODES_EXCEEDED");
            }
        }

        private int visitedNodes() {
            return visitedNodes;
        }
    }

    private static final class UnsupportedExpression
            extends RuntimeException {
        private UnsupportedExpression(String detailCode) {
            super(detailCode);
        }
    }

    private static final class BudgetExceeded
            extends RuntimeException {
        private BudgetExceeded(String detailCode) {
            super(detailCode);
        }
    }
}
