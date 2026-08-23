package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.transform.PatternExpr;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Exact, bounded polynomial-ring verifier for learned rewrite patterns.
 *
 * <p>Placeholders and literal variables are treated as independent commuting
 * indeterminates. Only integer coefficients and addition, subtraction,
 * multiplication and bounded non-negative integer powers are accepted. Every
 * unsupported construct fails closed instead of being sampled numerically.</p>
 */
public final class ExactPolynomialPatternIdentityVerifier {
    public static final String VERIFIER_ID =
        "regelsuche.exact-polynomial-pattern-identity/v1";

    private final Budget budget;

    public ExactPolynomialPatternIdentityVerifier() {
        this(Budget.DEFAULT);
    }

    public ExactPolynomialPatternIdentityVerifier(Budget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public Budget budget() {
        return budget;
    }

    public Verification verify(PatternExpr source, PatternExpr target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Work work = new Work();
        String sourcePattern = EvolutionGenomeCompiler.renderPattern(source);
        String targetPattern = EvolutionGenomeCompiler.renderPattern(target);
        String sourceCanonical = "";
        String targetCanonical = "";
        Status status;
        String detailCode;
        try {
            Polynomial sourcePolynomial = polynomial(source, work);
            sourceCanonical = sourcePolynomial.canonical();
            Polynomial targetPolynomial = polynomial(target, work);
            targetCanonical = targetPolynomial.canonical();
            if (sourcePolynomial.equals(targetPolynomial)) {
                status = Status.PROVED;
                detailCode = "EXACT_POLYNOMIAL_NORMAL_FORMS_EQUAL";
            } else {
                status = Status.NOT_EQUIVALENT;
                detailCode = "EXACT_POLYNOMIAL_NORMAL_FORMS_DIFFER";
            }
        } catch (BudgetExceeded exception) {
            status = Status.BUDGET_EXCEEDED;
            detailCode = exception.getMessage();
        } catch (UnsupportedPattern exception) {
            status = Status.UNSUPPORTED;
            detailCode = exception.getMessage();
        }
        Verification provisional = new Verification(
            VERIFIER_ID,
            status,
            budget,
            sourcePattern,
            targetPattern,
            sourceCanonical,
            targetCanonical,
            work.visitedNodes,
            work.generatedTerms,
            detailCode,
            "");
        return provisional.withProofHash(proofHash(provisional));
    }

    private Polynomial polynomial(PatternExpr expression, Work work) {
        work.visit(budget);
        if (expression instanceof PatternExpr.Placeholder placeholder) {
            return checked(
                Polynomial.variable("placeholder:" + placeholder.name()),
                work);
        }
        if (expression instanceof PatternExpr.LiteralVariable variable) {
            return checked(
                Polynomial.variable("literal:" + variable.name()),
                work);
        }
        if (expression instanceof PatternExpr.LiteralNumber number) {
            return checked(
                Polynomial.constant(exactInteger(number.value())),
                work);
        }
        if (expression instanceof PatternExpr.Function function) {
            throw unsupported("FUNCTION_NOT_IN_POLYNOMIAL_FRAGMENT_"
                + function.name());
        }
        PatternExpr.Operation operation =
            (PatternExpr.Operation) expression;
        if (operation.operator() == BinaryOperator.POW) {
            Polynomial base = polynomial(operation.left(), work);
            int exponent = exponent(operation.right(), work);
            return power(base, exponent, work);
        }
        if (operation.operator() == BinaryOperator.DIV) {
            throw unsupported("DIVISION_NOT_IN_POLYNOMIAL_FRAGMENT");
        }
        Polynomial left = polynomial(operation.left(), work);
        Polynomial right = polynomial(operation.right(), work);
        return switch (operation.operator()) {
            case ADD -> checked(left.add(right), work);
            case SUB -> checked(left.subtract(right), work);
            case MUL -> checked(left.multiply(right), work);
            case DIV, POW -> throw new IllegalStateException(
                "operator handled before switch");
        };
    }

    private Polynomial power(Polynomial base, int exponent, Work work) {
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
        return checked(result, work);
    }

    private Polynomial checked(Polynomial value, Work work) {
        if (value.terms().size() > budget.maxTerms()) {
            throw budget("MAX_TERMS_EXCEEDED");
        }
        for (Map.Entry<Monomial, BigInteger> term : value.terms().entrySet()) {
            if (term.getKey().degree() > budget.maxTotalDegree()) {
                throw budget("MAX_TOTAL_DEGREE_EXCEEDED");
            }
            if (term.getValue().abs().bitLength()
                    > budget.maxCoefficientBits()) {
                throw budget("MAX_COEFFICIENT_BITS_EXCEEDED");
            }
        }
        work.generatedTerms += value.terms().size();
        if (work.generatedTerms > budget.maxGeneratedTerms()) {
            throw budget("MAX_GENERATED_TERMS_EXCEEDED");
        }
        return value;
    }

    private int exponent(PatternExpr value, Work work) {
        work.visit(budget);
        if (!(value instanceof PatternExpr.LiteralNumber number)) {
            throw unsupported("POWER_EXPONENT_MUST_BE_LITERAL_INTEGER");
        }
        BigInteger integer = exactInteger(number.value());
        if (integer.signum() < 0
                || integer.compareTo(BigInteger.valueOf(
                    budget.maxExponent())) > 0) {
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

    private static UnsupportedPattern unsupported(String detailCode) {
        return new UnsupportedPattern(detailCode);
    }

    private static BudgetExceeded budget(String detailCode) {
        return new BudgetExceeded(detailCode);
    }

    private static String proofHash(Verification value) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, value.verifierId());
        append(descriptor, value.status().name());
        append(descriptor, value.budget().canonicalMaterial());
        append(descriptor, value.sourcePattern());
        append(descriptor, value.targetPattern());
        append(descriptor, value.sourceCanonical());
        append(descriptor, value.targetCanonical());
        append(descriptor, Integer.toString(value.visitedNodes()));
        append(descriptor, Integer.toString(value.generatedTerms()));
        append(descriptor, value.detailCode());
        return sha256(descriptor.toString());
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public enum Status {
        PROVED,
        NOT_EQUIVALENT,
        UNSUPPORTED,
        BUDGET_EXCEEDED
    }

    public record Budget(
        int maxVisitedNodes,
        int maxTerms,
        int maxGeneratedTerms,
        int maxTotalDegree,
        int maxExponent,
        int maxCoefficientBits
    ) {
        public static final Budget DEFAULT =
            new Budget(256, 256, 4_096, 32, 12, 256);

        public Budget {
            if (maxVisitedNodes < 1
                    || maxTerms < 1
                    || maxGeneratedTerms < 1
                    || maxTotalDegree < 0
                    || maxExponent < 0
                    || maxCoefficientBits < 1) {
                throw new IllegalArgumentException(
                    "polynomial verification budget is invalid");
            }
        }

        String canonicalMaterial() {
            return maxVisitedNodes + ":"
                + maxTerms + ":"
                + maxGeneratedTerms + ":"
                + maxTotalDegree + ":"
                + maxExponent + ":"
                + maxCoefficientBits;
        }
    }

    public record Verification(
        String verifierId,
        Status status,
        Budget budget,
        String sourcePattern,
        String targetPattern,
        String sourceCanonical,
        String targetCanonical,
        int visitedNodes,
        int generatedTerms,
        String detailCode,
        String proofHash
    ) {
        public Verification {
            if (!VERIFIER_ID.equals(verifierId)
                    || status == null
                    || budget == null
                    || sourcePattern == null
                    || sourcePattern.isBlank()
                    || targetPattern == null
                    || targetPattern.isBlank()
                    || sourceCanonical == null
                    || targetCanonical == null
                    || visitedNodes < 0
                    || generatedTerms < 0
                    || detailCode == null
                    || detailCode.isBlank()
                    || proofHash == null) {
                throw new IllegalArgumentException(
                    "polynomial verification identity is invalid");
            }
            if (!proofHash.isEmpty()
                    && !proofHash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "proofHash must be empty or SHA-256");
            }
        }

        public boolean proved() {
            return status == Status.PROVED;
        }

        private Verification withProofHash(String value) {
            return new Verification(
                verifierId,
                status,
                budget,
                sourcePattern,
                targetPattern,
                sourceCanonical,
                targetCanonical,
                visitedNodes,
                generatedTerms,
                detailCode,
                value);
        }
    }

    private static final class Work {
        private int visitedNodes;
        private int generatedTerms;

        private void visit(Budget budget) {
            visitedNodes++;
            if (visitedNodes > budget.maxVisitedNodes()) {
                throw budget("MAX_VISITED_NODES_EXCEEDED");
            }
        }
    }

    private record Polynomial(Map<Monomial, BigInteger> terms) {
        private Polynomial {
            Map<Monomial, BigInteger> normalized = new TreeMap<>();
            for (Map.Entry<Monomial, BigInteger> entry : terms.entrySet()) {
                if (entry.getValue().signum() != 0) {
                    normalized.merge(
                        entry.getKey(),
                        entry.getValue(),
                        BigInteger::add);
                }
            }
            normalized.entrySet().removeIf(entry ->
                entry.getValue().signum() == 0);
            terms = Map.copyOf(normalized);
        }

        static Polynomial constant(BigInteger value) {
            return value.signum() == 0
                ? new Polynomial(Map.of())
                : new Polynomial(Map.of(Monomial.ONE, value));
        }

        static Polynomial variable(String name) {
            return new Polynomial(Map.of(
                new Monomial(List.of(new Power(name, 1))),
                BigInteger.ONE));
        }

        Polynomial add(Polynomial other) {
            return combine(other, BigInteger.ONE);
        }

        Polynomial subtract(Polynomial other) {
            return combine(other, BigInteger.ONE.negate());
        }

        private Polynomial combine(
            Polynomial other,
            BigInteger otherScale
        ) {
            Map<Monomial, BigInteger> result =
                new LinkedHashMap<>(terms);
            other.terms.forEach((monomial, coefficient) ->
                result.merge(
                    monomial,
                    coefficient.multiply(otherScale),
                    BigInteger::add));
            return new Polynomial(result);
        }

        Polynomial multiply(Polynomial other) {
            Map<Monomial, BigInteger> result = new LinkedHashMap<>();
            for (Map.Entry<Monomial, BigInteger> left : terms.entrySet()) {
                for (Map.Entry<Monomial, BigInteger> right
                        : other.terms.entrySet()) {
                    result.merge(
                        left.getKey().multiply(right.getKey()),
                        left.getValue().multiply(right.getValue()),
                        BigInteger::add);
                }
            }
            return new Polynomial(result);
        }

        String canonical() {
            if (terms.isEmpty()) {
                return "0";
            }
            List<Map.Entry<Monomial, BigInteger>> ordered =
                new ArrayList<>(terms.entrySet());
            ordered.sort(Map.Entry.comparingByKey());
            return ordered.stream()
                .map(entry -> entry.getValue() + "*" + entry.getKey().canonical())
                .collect(java.util.stream.Collectors.joining("+"));
        }
    }

    private record Monomial(List<Power> powers)
            implements Comparable<Monomial> {
        private static final Monomial ONE = new Monomial(List.of());

        private Monomial {
            Map<String, Integer> normalized = new TreeMap<>();
            for (Power power : powers) {
                if (power.exponent() > 0) {
                    normalized.merge(
                        power.variable(),
                        power.exponent(),
                        Integer::sum);
                }
            }
            powers = normalized.entrySet().stream()
                .map(entry -> new Power(entry.getKey(), entry.getValue()))
                .toList();
        }

        Monomial multiply(Monomial other) {
            List<Power> combined = new ArrayList<>(powers);
            combined.addAll(other.powers);
            return new Monomial(combined);
        }

        int degree() {
            return powers.stream().mapToInt(Power::exponent).sum();
        }

        String canonical() {
            return powers.isEmpty()
                ? "1"
                : powers.stream()
                    .map(power -> power.variable() + "^" + power.exponent())
                    .collect(java.util.stream.Collectors.joining("*"));
        }

        @Override
        public int compareTo(Monomial other) {
            return canonical().compareTo(other.canonical());
        }
    }

    private record Power(String variable, int exponent) {
        private Power {
            if (variable == null || variable.isBlank() || exponent < 1) {
                throw new IllegalArgumentException(
                    "polynomial power is invalid");
            }
        }
    }

    private static final class UnsupportedPattern
            extends RuntimeException {
        private UnsupportedPattern(String message) {
            super(message);
        }
    }

    private static final class BudgetExceeded
            extends RuntimeException {
        private BudgetExceeded(String message) {
            super(message);
        }
    }
}
