package de.regelsuche.polynomial;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Exact bounded polynomial view over ordinary expression ASTs. */
public final class PolynomialSemanticView {
    public static final String VIEW_ID = "regelsuche.polynomial-semantic-view/v1";

    private final Budget budget;

    public PolynomialSemanticView() {
        this(Budget.DEFAULT);
    }

    public PolynomialSemanticView(Budget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public Result analyze(Expr expression) {
        Objects.requireNonNull(expression, "expression");
        MutableWork work = new MutableWork();
        Map<String, Expr> generators = new TreeMap<>();
        try {
            Polynomial polynomial = parse(expression, generators, work);
            List<Generator> ordered = generators.entrySet().stream()
                .map(entry -> new Generator(entry.getKey(), entry.getValue(), false))
                .toList();
            Work completed = work.snapshot();
            String canonical = canonical(polynomial, ordered, completed);
            View view = new View(
                polynomial,
                ordered,
                budget,
                completed,
                canonical,
                sha256(canonical));
            return new Result(Status.SUPPORTED, view, "EXACT_INTEGER_POLYNOMIAL");
        } catch (Unsupported exception) {
            return new Result(Status.UNSUPPORTED, null, exception.getMessage());
        } catch (BudgetExceeded exception) {
            return new Result(Status.BUDGET_EXCEEDED, null, exception.getMessage());
        }
    }

    private Polynomial parse(
        Expr expression,
        Map<String, Expr> generators,
        MutableWork work
    ) {
        work.visit(budget);
        if (expression instanceof NumberExpr number) {
            return checked(Polynomial.constant(exactInteger(number.value())), work);
        }
        if (expression instanceof VariableExpr || expression instanceof FunctionExpr) {
            return generator(expression, generators, work);
        }
        BinaryExpr binary = (BinaryExpr) expression;
        return switch (binary.operator()) {
            case ADD -> checked(
                parse(binary.left(), generators, work)
                    .add(parse(binary.right(), generators, work)),
                work);
            case SUB -> checked(
                parse(binary.left(), generators, work)
                    .subtract(parse(binary.right(), generators, work)),
                work);
            case MUL -> checked(
                parse(binary.left(), generators, work)
                    .multiply(parse(binary.right(), generators, work)),
                work);
            case POW -> power(binary, generators, work);
            case DIV -> throw unsupported("DIVISION_NOT_IN_EXACT_POLYNOMIAL_VIEW");
        };
    }

    private Polynomial power(
        BinaryExpr expression,
        Map<String, Expr> generators,
        MutableWork work
    ) {
        int exponent = exponent(expression.right(), work);
        if (exponent == 0) {
            return checked(Polynomial.constant(BigInteger.ONE), work);
        }
        Polynomial base = preservePowerBase(expression.left())
            ? generator(expression.left(), generators, work)
            : parse(expression.left(), generators, work);
        Polynomial result = Polynomial.constant(BigInteger.ONE);
        Polynomial factor = base;
        int remaining = exponent;
        while (remaining > 0) {
            if ((remaining & 1) == 1) {
                result = checked(result.multiply(factor), work);
            }
            remaining >>= 1;
            if (remaining > 0) {
                factor = checked(factor.multiply(factor), work);
            }
        }
        return result;
    }

    private boolean preservePowerBase(Expr expression) {
        if (expression instanceof FunctionExpr) {
            return true;
        }
        return expression instanceof BinaryExpr binary
            && (binary.operator() == BinaryOperator.ADD
                || binary.operator() == BinaryOperator.SUB);
    }

    private Polynomial generator(
        Expr expression,
        Map<String, Expr> generators,
        MutableWork work
    ) {
        if (containsDivision(expression)) {
            throw unsupported("GENERATOR_CONTAINS_DIVISION");
        }
        String key = ExpressionFormatter.format(expression);
        if (!generators.containsKey(key) && generators.size() >= budget.maxGenerators()) {
            throw budget("MAX_GENERATORS_EXCEEDED");
        }
        generators.putIfAbsent(key, expression);
        return checked(Polynomial.generator(key), work);
    }

    private boolean containsDivision(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            return binary.operator() == BinaryOperator.DIV
                || containsDivision(binary.left())
                || containsDivision(binary.right());
        }
        return expression instanceof FunctionExpr function
            && function.arguments().stream().anyMatch(this::containsDivision);
    }

    private int exponent(Expr expression, MutableWork work) {
        work.visit(budget);
        if (!(expression instanceof NumberExpr number)) {
            throw unsupported("POWER_EXPONENT_MUST_BE_LITERAL_INTEGER");
        }
        BigInteger value = exactInteger(number.value());
        if (value.signum() < 0
                || value.compareTo(BigInteger.valueOf(budget.maxExponent())) > 0) {
            throw unsupported("POWER_EXPONENT_OUTSIDE_SUPPORTED_RANGE");
        }
        return value.intValueExact();
    }

    private Polynomial checked(Polynomial polynomial, MutableWork work) {
        if (polynomial.terms().size() > budget.maxTerms()) {
            throw budget("MAX_TERMS_EXCEEDED");
        }
        if (polynomial.totalDegree() > budget.maxTotalDegree()) {
            throw budget("MAX_TOTAL_DEGREE_EXCEEDED");
        }
        if (polynomial.terms().values().stream().anyMatch(coefficient ->
                coefficient.abs().bitLength() > budget.maxCoefficientBits())) {
            throw budget("MAX_COEFFICIENT_BITS_EXCEEDED");
        }
        work.generate(polynomial.terms().size(), budget);
        return polynomial;
    }

    private String canonical(
        Polynomial polynomial,
        List<Generator> generators,
        Work work
    ) {
        StringBuilder material = new StringBuilder();
        append(material, VIEW_ID);
        append(material, budget.canonicalMaterial());
        generators.forEach(generator -> {
            append(material, generator.key());
            append(material, Boolean.toString(generator.synthetic()));
        });
        append(material, polynomial.canonical());
        append(material, Integer.toString(work.visitedNodes()));
        append(material, Integer.toString(work.generatedTerms()));
        return material.toString();
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

    private static Unsupported unsupported(String code) {
        return new Unsupported(code);
    }

    private static BudgetExceeded budget(String code) {
        return new BudgetExceeded(code);
    }

    private static void append(StringBuilder target, String value) {
        String safe = value == null ? "" : value;
        target.append(safe.length()).append(':').append(safe);
    }

    public static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public enum Status {
        SUPPORTED,
        UNSUPPORTED,
        BUDGET_EXCEEDED
    }

    public record Budget(
        int maxVisitedNodes,
        int maxTerms,
        int maxGeneratedTerms,
        int maxGenerators,
        int maxTotalDegree,
        int maxExponent,
        int maxCoefficientBits
    ) {
        public static final Budget DEFAULT =
            new Budget(512, 256, 8_192, 8, 16, 16, 52);

        public Budget {
            if (maxVisitedNodes < 1 || maxTerms < 1 || maxGeneratedTerms < 1
                    || maxGenerators < 1 || maxTotalDegree < 0
                    || maxExponent < 0 || maxCoefficientBits < 1) {
                throw new IllegalArgumentException("invalid polynomial semantic-view budget");
            }
        }

        public String canonicalMaterial() {
            return maxVisitedNodes + ":" + maxTerms + ":" + maxGeneratedTerms
                + ":" + maxGenerators + ":" + maxTotalDegree + ":"
                + maxExponent + ":" + maxCoefficientBits;
        }
    }

    public record Work(int visitedNodes, int generatedTerms) {
        public Work {
            if (visitedNodes < 0 || generatedTerms < 0) {
                throw new IllegalArgumentException("work counters must be non-negative");
            }
        }
    }

    public record Generator(String key, Expr expression, boolean synthetic) {
        public Generator {
            if (key == null || key.isBlank() || expression == null) {
                throw new IllegalArgumentException("generator key and expression are required");
            }
        }
    }

    public record View(
        Polynomial polynomial,
        List<Generator> generators,
        Budget budget,
        Work work,
        String canonicalMaterial,
        String semanticHash
    ) {
        public View {
            Objects.requireNonNull(polynomial, "polynomial");
            generators = List.copyOf(generators);
            Objects.requireNonNull(budget, "budget");
            Objects.requireNonNull(work, "work");
            if (canonicalMaterial == null || canonicalMaterial.isBlank()
                    || semanticHash == null
                    || !semanticHash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid polynomial view identity");
            }
        }

        public Map<String, Expr> generatorExpressions() {
            Map<String, Expr> result = new LinkedHashMap<>();
            generators.forEach(generator ->
                result.put(generator.key(), generator.expression()));
            return Collections.unmodifiableMap(result);
        }
    }

    public record Result(Status status, View view, String detailCode) {
        public Result {
            Objects.requireNonNull(status, "status");
            detailCode = detailCode == null ? "" : detailCode;
            if (status == Status.SUPPORTED && view == null) {
                throw new IllegalArgumentException("supported result requires a view");
            }
            if (status != Status.SUPPORTED && view != null) {
                throw new IllegalArgumentException("failed result must not expose a view");
            }
        }

        public boolean supported() {
            return status == Status.SUPPORTED;
        }
    }

    public record Monomial(Map<String, Integer> exponents)
            implements Comparable<Monomial> {
        public static final Monomial ONE = new Monomial(Map.of());

        public Monomial {
            Map<String, Integer> normalized = new TreeMap<>();
            if (exponents != null) {
                exponents.forEach((key, exponent) -> {
                    if (key == null || key.isBlank() || exponent == null || exponent < 0) {
                        throw new IllegalArgumentException("invalid monomial exponent");
                    }
                    if (exponent > 0) {
                        normalized.put(key, exponent);
                    }
                });
            }
            exponents = Collections.unmodifiableMap(normalized);
        }

        public static Monomial generator(String key) {
            return new Monomial(Map.of(key, 1));
        }

        public int exponent(String key) {
            return exponents.getOrDefault(key, 0);
        }

        public int degree() {
            return exponents.values().stream().mapToInt(Integer::intValue).sum();
        }

        public Monomial multiply(Monomial other) {
            Map<String, Integer> result = new TreeMap<>(exponents);
            other.exponents.forEach((key, exponent) ->
                result.merge(key, exponent, Math::addExact));
            return new Monomial(result);
        }

        public Monomial divide(Monomial divisor) {
            Map<String, Integer> result = new TreeMap<>(exponents);
            divisor.exponents.forEach((key, exponent) -> {
                int remaining = result.getOrDefault(key, 0) - exponent;
                if (remaining < 0) {
                    throw new IllegalArgumentException("monomial is not divisible");
                }
                if (remaining == 0) {
                    result.remove(key);
                } else {
                    result.put(key, remaining);
                }
            });
            return new Monomial(result);
        }

        public String canonical() {
            return exponents.isEmpty()
                ? "1"
                : exponents.entrySet().stream()
                    .map(entry -> entry.getKey() + "^" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining("*"));
        }

        @Override
        public int compareTo(Monomial other) {
            return Comparator.comparingInt(Monomial::degree)
                .thenComparing(Monomial::canonical)
                .compare(this, other);
        }
    }

    public record Polynomial(Map<Monomial, BigInteger> terms) {
        public Polynomial {
            Map<Monomial, BigInteger> normalized = new TreeMap<>();
            if (terms != null) {
                terms.forEach((monomial, coefficient) -> {
                    if (monomial == null || coefficient == null) {
                        throw new IllegalArgumentException("polynomial term is null");
                    }
                    if (coefficient.signum() != 0) {
                        normalized.merge(monomial, coefficient, BigInteger::add);
                    }
                });
            }
            normalized.entrySet().removeIf(entry -> entry.getValue().signum() == 0);
            terms = Collections.unmodifiableMap(normalized);
        }

        public static Polynomial constant(BigInteger value) {
            return value.signum() == 0
                ? new Polynomial(Map.of())
                : new Polynomial(Map.of(Monomial.ONE, value));
        }

        public static Polynomial generator(String key) {
            return new Polynomial(Map.of(Monomial.generator(key), BigInteger.ONE));
        }

        public Polynomial add(Polynomial other) {
            Map<Monomial, BigInteger> result = new LinkedHashMap<>(terms);
            other.terms.forEach((monomial, coefficient) ->
                result.merge(monomial, coefficient, BigInteger::add));
            return new Polynomial(result);
        }

        public Polynomial subtract(Polynomial other) {
            return add(other.scale(BigInteger.ONE.negate()));
        }

        public Polynomial scale(BigInteger factor) {
            if (factor.signum() == 0 || terms.isEmpty()) {
                return constant(BigInteger.ZERO);
            }
            Map<Monomial, BigInteger> result = new LinkedHashMap<>();
            terms.forEach((monomial, coefficient) ->
                result.put(monomial, coefficient.multiply(factor)));
            return new Polynomial(result);
        }

        public Polynomial multiply(Polynomial other) {
            Map<Monomial, BigInteger> result = new LinkedHashMap<>();
            for (Map.Entry<Monomial, BigInteger> left : terms.entrySet()) {
                for (Map.Entry<Monomial, BigInteger> right : other.terms.entrySet()) {
                    result.merge(
                        left.getKey().multiply(right.getKey()),
                        left.getValue().multiply(right.getValue()),
                        BigInteger::add);
                }
            }
            return new Polynomial(result);
        }

        public int totalDegree() {
            return terms.keySet().stream().mapToInt(Monomial::degree).max().orElse(0);
        }

        public boolean homogeneous() {
            if (terms.isEmpty()) {
                return false;
            }
            int degree = terms.keySet().iterator().next().degree();
            return terms.keySet().stream().allMatch(term -> term.degree() == degree);
        }

        public BigInteger content() {
            BigInteger gcd = BigInteger.ZERO;
            for (BigInteger coefficient : terms.values()) {
                gcd = gcd.gcd(coefficient.abs());
            }
            return gcd;
        }

        public Monomial commonMonomial() {
            if (terms.isEmpty()) {
                return Monomial.ONE;
            }
            Map<String, Integer> minima = new TreeMap<>(
                terms.keySet().iterator().next().exponents());
            for (Monomial monomial : terms.keySet()) {
                for (String key : new ArrayList<>(minima.keySet())) {
                    int minimum = Math.min(minima.get(key), monomial.exponent(key));
                    if (minimum == 0) {
                        minima.remove(key);
                    } else {
                        minima.put(key, minimum);
                    }
                }
            }
            return new Monomial(minima);
        }

        public Polynomial divideByContent(BigInteger divisor) {
            if (divisor.signum() == 0) {
                throw new IllegalArgumentException("content divisor is zero");
            }
            Map<Monomial, BigInteger> result = new LinkedHashMap<>();
            terms.forEach((monomial, coefficient) -> {
                BigInteger[] division = coefficient.divideAndRemainder(divisor);
                if (division[1].signum() != 0) {
                    throw new IllegalArgumentException("coefficient is not divisible");
                }
                result.put(monomial, division[0]);
            });
            return new Polynomial(result);
        }

        public Polynomial divideByMonomial(Monomial divisor) {
            Map<Monomial, BigInteger> result = new LinkedHashMap<>();
            terms.forEach((monomial, coefficient) ->
                result.put(monomial.divide(divisor), coefficient));
            return new Polynomial(result);
        }

        public String canonical() {
            return terms.isEmpty()
                ? "0"
                : terms.entrySet().stream()
                    .map(entry -> entry.getValue() + "*" + entry.getKey().canonical())
                    .collect(java.util.stream.Collectors.joining("+"));
        }

        public Expr toExpr(Map<String, Expr> generators) {
            if (terms.isEmpty()) {
                return new NumberExpr(0);
            }
            Expr result = null;
            for (Map.Entry<Monomial, BigInteger> entry : terms.entrySet()) {
                BigInteger coefficient = entry.getValue();
                Expr body = monomialExpr(entry.getKey(), generators);
                Expr term = withCoefficient(coefficient.abs(), body);
                if (result == null) {
                    result = coefficient.signum() < 0
                        ? new BinaryExpr(new NumberExpr(0), BinaryOperator.SUB, term)
                        : term;
                } else {
                    result = new BinaryExpr(
                        result,
                        coefficient.signum() < 0 ? BinaryOperator.SUB : BinaryOperator.ADD,
                        term);
                }
            }
            return result;
        }

        private static Expr monomialExpr(
            Monomial monomial,
            Map<String, Expr> generators
        ) {
            if (monomial.exponents().isEmpty()) {
                return new NumberExpr(1);
            }
            List<Expr> factors = new ArrayList<>();
            monomial.exponents().forEach((key, exponent) -> {
                Expr generator = generators.get(key);
                if (generator == null) {
                    throw new IllegalArgumentException("missing generator " + key);
                }
                factors.add(exponent == 1
                    ? generator
                    : new BinaryExpr(generator, BinaryOperator.POW, new NumberExpr(exponent)));
            });
            return leftAssociate(factors, BinaryOperator.MUL);
        }

        private static Expr withCoefficient(BigInteger coefficient, Expr body) {
            double value = coefficient.doubleValue();
            if (!Double.isFinite(value) || BigDecimal.valueOf(value).toBigInteger().compareTo(coefficient) != 0) {
                throw new IllegalArgumentException("coefficient cannot be represented by AST number");
            }
            if (body instanceof NumberExpr number && Double.compare(number.value(), 1.0) == 0) {
                return new NumberExpr(value);
            }
            return coefficient.equals(BigInteger.ONE)
                ? body
                : new BinaryExpr(new NumberExpr(value), BinaryOperator.MUL, body);
        }

        private static Expr leftAssociate(List<Expr> expressions, BinaryOperator operator) {
            Expr result = expressions.getFirst();
            for (int index = 1; index < expressions.size(); index++) {
                result = new BinaryExpr(result, operator, expressions.get(index));
            }
            return result;
        }
    }

    private static final class MutableWork {
        private int visitedNodes;
        private int generatedTerms;

        private void visit(Budget budget) {
            if (++visitedNodes > budget.maxVisitedNodes()) {
                throw budget("MAX_VISITED_NODES_EXCEEDED");
            }
        }

        private void generate(int count, Budget budget) {
            try {
                generatedTerms = Math.addExact(generatedTerms, count);
            } catch (ArithmeticException exception) {
                throw budget("MAX_GENERATED_TERMS_EXCEEDED");
            }
            if (generatedTerms > budget.maxGeneratedTerms()) {
                throw budget("MAX_GENERATED_TERMS_EXCEEDED");
            }
        }

        private Work snapshot() {
            return new Work(visitedNodes, generatedTerms);
        }
    }

    private static final class Unsupported extends RuntimeException {
        private Unsupported(String code) {
            super(code);
        }
    }

    private static final class BudgetExceeded extends RuntimeException {
        private BudgetExceeded(String code) {
            super(code);
        }
    }
}
