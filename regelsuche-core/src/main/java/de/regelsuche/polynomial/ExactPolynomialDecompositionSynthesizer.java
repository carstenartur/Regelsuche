package de.regelsuche.polynomial;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.polynomial.PolynomialSemanticView.Generator;
import de.regelsuche.polynomial.PolynomialSemanticView.Monomial;
import de.regelsuche.polynomial.PolynomialSemanticView.Polynomial;
import de.regelsuche.polynomial.PolynomialSemanticView.View;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded exact decomposition synthesis for integer polynomials.
 *
 * <p>The v1 procedure factors homogeneous bivariate polynomials, and ordinary
 * univariate polynomials through an implicit unit generator, by enumerating
 * bounded factor coefficient templates and checking them with exact polynomial
 * division. No named identity or target coefficient tuple is embedded.</p>
 */
public final class ExactPolynomialDecompositionSynthesizer {
    public static final String ALGORITHM_ID =
        "regelsuche.exact-polynomial-decomposition-synthesis/v1";
    private static final String UNIT_GENERATOR_KEY = "$unit";

    private final Budget budget;

    public ExactPolynomialDecompositionSynthesizer() {
        this(Budget.DEFAULT);
    }

    public ExactPolynomialDecompositionSynthesizer(Budget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public Budget budget() {
        return budget;
    }

    public Result synthesize(View view) {
        Objects.requireNonNull(view, "view");
        MutableWork work = new MutableWork();
        try {
            Prepared prepared = prepare(view);
            Map<String, Candidate> candidates = new LinkedHashMap<>();
            enumerateFactors(prepared, work, candidates);
            if (candidates.isEmpty()) {
                contentCandidate(prepared, work).ifPresent(candidate ->
                    candidates.put(candidate.canonicalPair(), candidate));
            }
            List<Candidate> ordered = candidates.values().stream()
                .sorted(Comparator.comparingInt(Candidate::score)
                    .thenComparing(candidate ->
                        ExpressionFormatter.format(candidate.factoredExpression())))
                .limit(budget.maxCandidates())
                .toList();
            return new Result(
                ordered.isEmpty()
                    ? Status.NO_DECOMPOSITION_FOUND
                    : Status.SYNTHESIZED,
                ordered,
                work.snapshot(),
                ordered.isEmpty()
                    ? "NO_EXACT_FACTOR_WITHIN_TEMPLATE_BUDGET"
                    : "EXACT_FACTORISATION_SYNTHESIZED");
        } catch (UnsupportedSubject exception) {
            return new Result(
                Status.UNSUPPORTED,
                List.of(),
                work.snapshot(),
                exception.getMessage());
        } catch (BudgetExceeded exception) {
            return new Result(
                Status.BUDGET_EXCEEDED,
                List.of(),
                work.snapshot(),
                exception.getMessage());
        }
    }

    private Prepared prepare(View view) {
        if (view.generators().isEmpty()) {
            throw unsupported("CONSTANT_POLYNOMIAL_HAS_NO_DECOMPOSITION_SURFACE");
        }
        if (view.generators().size() > 2) {
            throw unsupported("MORE_THAN_TWO_SEMANTIC_GENERATORS");
        }
        Polynomial source = view.polynomial();
        if (source.terms().size() < 2) {
            throw unsupported("POLYNOMIAL_HAS_FEWER_THAN_TWO_TERMS");
        }
        BigInteger content = source.content();
        if (content.signum() == 0) {
            throw unsupported("ZERO_POLYNOMIAL_NOT_SYNTHESIZED");
        }
        Monomial commonMonomial = source.commonMonomial();
        Polynomial primitive = source
            .divideByContent(content)
            .divideByMonomial(commonMonomial);

        List<Generator> generators = new ArrayList<>(view.generators());
        boolean implicitUnit = generators.size() == 1;
        if (implicitUnit) {
            generators.add(new Generator(
                UNIT_GENERATOR_KEY,
                new NumberExpr(1),
                true));
        } else if (!primitive.homogeneous()) {
            throw unsupported("BIVARIATE_POLYNOMIAL_MUST_BE_HOMOGENEOUS");
        }

        int degree = primitive.totalDegree();
        if (degree < 2) {
            throw unsupported("POLYNOMIAL_DEGREE_BELOW_TWO");
        }
        if (degree > budget.maxTotalDegree()) {
            throw budget("MAX_SYNTHESIS_DEGREE_EXCEEDED");
        }

        String firstKey = generators.get(0).key();
        String secondKey = generators.get(1).key();
        BigInteger[] coefficients = coefficients(
            primitive,
            firstKey,
            secondKey,
            degree,
            implicitUnit);
        BigInteger numericContent = content;
        if (coefficients[degree].signum() < 0) {
            numericContent = numericContent.negate();
            for (int index = 0; index < coefficients.length; index++) {
                coefficients[index] = coefficients[index].negate();
            }
        }
        if (coefficients[0].signum() == 0
                || coefficients[degree].signum() == 0) {
            throw unsupported("CONTENT_EXTRACTION_DID_NOT_EXPOSE_BOUNDARY_TERMS");
        }
        return new Prepared(
            view,
            List.copyOf(generators),
            primitive,
            commonMonomial,
            numericContent,
            coefficients,
            degree,
            implicitUnit);
    }

    private BigInteger[] coefficients(
        Polynomial polynomial,
        String firstKey,
        String secondKey,
        int degree,
        boolean implicitUnit
    ) {
        BigInteger[] coefficients = new BigInteger[degree + 1];
        Arrays.fill(coefficients, BigInteger.ZERO);
        for (Map.Entry<Monomial, BigInteger> term :
                polynomial.terms().entrySet()) {
            Monomial monomial = term.getKey();
            int firstExponent = monomial.exponent(firstKey);
            int secondExponent = implicitUnit
                ? degree - firstExponent
                : monomial.exponent(secondKey);
            if (firstExponent < 0
                    || secondExponent < 0
                    || firstExponent + secondExponent != degree) {
                throw unsupported("TERM_OUTSIDE_HOMOGENEOUS_BIVARIATE_SURFACE");
            }
            for (String key : monomial.exponents().keySet()) {
                if (!key.equals(firstKey) && !key.equals(secondKey)) {
                    throw unsupported("TERM_CONTAINS_UNDECLARED_GENERATOR");
                }
            }
            coefficients[firstExponent] = coefficients[firstExponent]
                .add(term.getValue());
        }
        return coefficients;
    }

    private void enumerateFactors(
        Prepared prepared,
        MutableWork work,
        Map<String, Candidate> candidates
    ) {
        int maximumFactorDegree = Math.min(
            prepared.degree() / 2,
            budget.maxFactorDegree());
        for (int factorDegree = 1;
                factorDegree <= maximumFactorDegree;
                factorDegree++) {
            List<BigInteger> leadingDivisors = positiveDivisors(
                prepared.coefficients()[prepared.degree()].abs());
            List<BigInteger> constantDivisors = signedDivisors(
                prepared.coefficients()[0].abs());
            for (BigInteger leading : leadingDivisors) {
                for (BigInteger constant : constantDivisors) {
                    BigInteger[] factor = new BigInteger[factorDegree + 1];
                    Arrays.fill(factor, BigInteger.ZERO);
                    factor[0] = constant;
                    factor[factorDegree] = leading;
                    enumerateMiddle(
                        prepared,
                        work,
                        candidates,
                        factor,
                        1,
                        factorDegree);
                }
            }
        }
    }

    private void enumerateMiddle(
        Prepared prepared,
        MutableWork work,
        Map<String, Candidate> candidates,
        BigInteger[] factor,
        int position,
        int factorDegree
    ) {
        if (position >= factorDegree) {
            considerFactor(prepared, work, candidates, factor);
            return;
        }
        for (int coefficient = -budget.maxCoefficientAbs();
                coefficient <= budget.maxCoefficientAbs();
                coefficient++) {
            factor[position] = BigInteger.valueOf(coefficient);
            enumerateMiddle(
                prepared,
                work,
                candidates,
                factor,
                position + 1,
                factorDegree);
        }
    }

    private void considerFactor(
        Prepared prepared,
        MutableWork work,
        Map<String, Candidate> candidates,
        BigInteger[] factor
    ) {
        work.enumerate(budget);
        if (!primitive(factor)) {
            return;
        }
        BigInteger[] quotient = divideExact(prepared.coefficients(), factor);
        work.divide();
        if (quotient == null || degree(quotient) < 1) {
            return;
        }
        if (!withinCoefficientBudget(quotient)) {
            return;
        }
        BigInteger[] normalizedQuotient = trim(quotient);
        BigInteger[] factorCopy = Arrays.copyOf(factor, factor.length);
        String factorCanonical = coefficientsCanonical(factorCopy);
        String quotientCanonical = coefficientsCanonical(normalizedQuotient);
        String canonicalPair = factorCanonical.compareTo(quotientCanonical) <= 0
            ? factorCanonical + "|" + quotientCanonical
            : quotientCanonical + "|" + factorCanonical;
        if (candidates.containsKey(canonicalPair)) {
            return;
        }

        Candidate provisional = candidate(
            prepared,
            work,
            factorCopy,
            normalizedQuotient,
            canonicalPair,
            "EXACT_TEMPLATE_DIVISION");
        candidates.put(canonicalPair, provisional);
        work.accept();
    }

    private java.util.Optional<Candidate> contentCandidate(
        Prepared prepared,
        MutableWork work
    ) {
        boolean nonTrivialContent = !prepared.numericContent().equals(BigInteger.ONE);
        boolean nonTrivialMonomial = !prepared.commonMonomial().equals(Monomial.ONE);
        if (!nonTrivialContent && !nonTrivialMonomial) {
            return java.util.Optional.empty();
        }
        Expr outer = outerFactor(prepared);
        Expr residual = prepared.primitive().toExpr(generatorMap(prepared.generators()));
        Expr factored = new BinaryExpr(outer, BinaryOperator.MUL, residual);
        String canonicalPair = "content|" + prepared.primitive().canonical();
        Certificate certificate = certificate(
            prepared,
            work.snapshot(),
            List.of(prepared.numericContent().toString()),
            List.of(prepared.primitive().canonical()),
            canonicalPair,
            "EXACT_CONTENT_EXTRACTION");
        return java.util.Optional.of(new Candidate(
            factored,
            outer,
            residual,
            certificate,
            canonicalPair,
            nodeCount(factored)));
    }

    private Candidate candidate(
        Prepared prepared,
        MutableWork work,
        BigInteger[] factor,
        BigInteger[] quotient,
        String canonicalPair,
        String method
    ) {
        Expr first = homogeneousExpr(
            factor,
            prepared.generators().get(0).expression(),
            prepared.generators().get(1).expression());
        Expr second = homogeneousExpr(
            quotient,
            prepared.generators().get(0).expression(),
            prepared.generators().get(1).expression());
        Expr product = new BinaryExpr(first, BinaryOperator.MUL, second);
        Expr outer = outerFactor(prepared);
        Expr factored = isOne(outer)
            ? product
            : new BinaryExpr(outer, BinaryOperator.MUL, product);
        Certificate certificate = certificate(
            prepared,
            work.snapshot(),
            stringCoefficients(factor),
            stringCoefficients(quotient),
            canonicalPair,
            method);
        int coefficientPenalty = maximumAbs(factor) + maximumAbs(quotient);
        int balancePenalty = Math.abs((factor.length - 1) - (quotient.length - 1));
        int score = nodeCount(factored) + coefficientPenalty + balancePenalty;
        return new Candidate(
            factored,
            first,
            second,
            certificate,
            canonicalPair,
            score);
    }

    private Certificate certificate(
        Prepared prepared,
        Work work,
        List<String> factorCoefficients,
        List<String> quotientCoefficients,
        String canonicalPair,
        String method
    ) {
        StringBuilder material = new StringBuilder();
        append(material, ALGORITHM_ID);
        append(material, prepared.view().semanticHash());
        prepared.generators().forEach(generator -> {
            append(material, generator.key());
            append(material, Boolean.toString(generator.synthetic()));
        });
        append(material, prepared.numericContent().toString());
        append(material, prepared.commonMonomial().canonical());
        append(material, Integer.toString(prepared.degree()));
        factorCoefficients.forEach(value -> append(material, value));
        quotientCoefficients.forEach(value -> append(material, value));
        append(material, canonicalPair);
        append(material, method);
        append(material, budget.canonicalMaterial());
        append(material, work.canonicalMaterial());
        String canonicalMaterial = material.toString();
        return new Certificate(
            ALGORITHM_ID,
            prepared.view().semanticHash(),
            prepared.generators().stream().map(Generator::key).toList(),
            prepared.numericContent().toString(),
            prepared.commonMonomial().canonical(),
            prepared.degree(),
            factorCoefficients,
            quotientCoefficients,
            method,
            budget,
            work,
            PolynomialSemanticView.sha256(canonicalMaterial));
    }

    private Expr outerFactor(Prepared prepared) {
        Polynomial factor = new Polynomial(Map.of(
            prepared.commonMonomial(),
            prepared.numericContent()));
        return factor.toExpr(generatorMap(prepared.generators()));
    }

    private Map<String, Expr> generatorMap(List<Generator> generators) {
        Map<String, Expr> values = new LinkedHashMap<>();
        generators.forEach(generator ->
            values.put(generator.key(), generator.expression()));
        return values;
    }

    private Expr homogeneousExpr(
        BigInteger[] coefficients,
        Expr firstGenerator,
        Expr secondGenerator
    ) {
        int degree = coefficients.length - 1;
        Expr result = null;
        for (int firstExponent = degree;
                firstExponent >= 0;
                firstExponent--) {
            BigInteger coefficient = coefficients[firstExponent];
            if (coefficient.signum() == 0) {
                continue;
            }
            int secondExponent = degree - firstExponent;
            Expr monomial = product(
                power(firstGenerator, firstExponent),
                power(secondGenerator, secondExponent));
            Expr term = coefficientTerm(coefficient.abs(), monomial);
            if (result == null) {
                result = coefficient.signum() < 0
                    ? new BinaryExpr(new NumberExpr(0), BinaryOperator.SUB, term)
                    : term;
            } else {
                result = new BinaryExpr(
                    result,
                    coefficient.signum() < 0
                        ? BinaryOperator.SUB
                        : BinaryOperator.ADD,
                    term);
            }
        }
        if (result == null) {
            throw new IllegalStateException("zero factor was synthesized");
        }
        return result;
    }

    private Expr power(Expr generator, int exponent) {
        if (exponent == 0 || isOne(generator)) {
            return new NumberExpr(1);
        }
        return exponent == 1
            ? generator
            : new BinaryExpr(
                generator,
                BinaryOperator.POW,
                new NumberExpr(exponent));
    }

    private Expr product(Expr left, Expr right) {
        if (isOne(left)) {
            return right;
        }
        if (isOne(right)) {
            return left;
        }
        return new BinaryExpr(left, BinaryOperator.MUL, right);
    }

    private Expr coefficientTerm(BigInteger coefficient, Expr monomial) {
        if (isOne(monomial)) {
            return new NumberExpr(coefficient.doubleValue());
        }
        return coefficient.equals(BigInteger.ONE)
            ? monomial
            : new BinaryExpr(
                new NumberExpr(coefficient.doubleValue()),
                BinaryOperator.MUL,
                monomial);
    }

    private boolean isOne(Expr expression) {
        return expression instanceof NumberExpr number
            && Double.compare(number.value(), 1.0) == 0;
    }

    private BigInteger[] divideExact(
        BigInteger[] dividend,
        BigInteger[] divisor
    ) {
        int dividendDegree = degree(dividend);
        int divisorDegree = degree(divisor);
        if (divisorDegree < 1 || dividendDegree < divisorDegree) {
            return null;
        }
        BigInteger[] remainder = Arrays.copyOf(
            dividend,
            dividendDegree + 1);
        BigInteger[] quotient = new BigInteger[
            dividendDegree - divisorDegree + 1];
        Arrays.fill(quotient, BigInteger.ZERO);
        BigInteger divisorLeading = divisor[divisorDegree];
        for (int shift = dividendDegree - divisorDegree;
                shift >= 0;
                shift--) {
            BigInteger leading = remainder[divisorDegree + shift];
            BigInteger[] division = leading.divideAndRemainder(divisorLeading);
            if (division[1].signum() != 0) {
                return null;
            }
            BigInteger factor = division[0];
            quotient[shift] = factor;
            for (int index = 0; index <= divisorDegree; index++) {
                remainder[index + shift] = remainder[index + shift]
                    .subtract(factor.multiply(divisor[index]));
            }
        }
        for (BigInteger coefficient : remainder) {
            if (coefficient.signum() != 0) {
                return null;
            }
        }
        return trim(quotient);
    }

    private boolean primitive(BigInteger[] coefficients) {
        BigInteger gcd = BigInteger.ZERO;
        for (BigInteger coefficient : coefficients) {
            gcd = gcd.gcd(coefficient.abs());
        }
        return gcd.equals(BigInteger.ONE);
    }

    private boolean withinCoefficientBudget(BigInteger[] coefficients) {
        BigInteger maximum = BigInteger.valueOf(budget.maxCoefficientAbs());
        return Arrays.stream(coefficients)
            .allMatch(value -> value.abs().compareTo(maximum) <= 0);
    }

    private List<BigInteger> positiveDivisors(BigInteger value) {
        List<BigInteger> result = new ArrayList<>();
        for (int candidate = 1;
                candidate <= budget.maxCoefficientAbs();
                candidate++) {
            BigInteger divisor = BigInteger.valueOf(candidate);
            if (value.mod(divisor).signum() == 0) {
                result.add(divisor);
            }
        }
        return result;
    }

    private List<BigInteger> signedDivisors(BigInteger value) {
        List<BigInteger> positive = positiveDivisors(value);
        List<BigInteger> result = new ArrayList<>();
        positive.forEach(divisor -> {
            result.add(divisor.negate());
            result.add(divisor);
        });
        return result;
    }

    private int degree(BigInteger[] coefficients) {
        for (int index = coefficients.length - 1; index >= 0; index--) {
            if (coefficients[index].signum() != 0) {
                return index;
            }
        }
        return -1;
    }

    private BigInteger[] trim(BigInteger[] coefficients) {
        int degree = degree(coefficients);
        return degree < 0
            ? new BigInteger[] {BigInteger.ZERO}
            : Arrays.copyOf(coefficients, degree + 1);
    }

    private String coefficientsCanonical(BigInteger[] coefficients) {
        return Arrays.stream(coefficients)
            .map(BigInteger::toString)
            .collect(java.util.stream.Collectors.joining(","));
    }

    private List<String> stringCoefficients(BigInteger[] coefficients) {
        return Arrays.stream(coefficients).map(BigInteger::toString).toList();
    }

    private int maximumAbs(BigInteger[] coefficients) {
        return Arrays.stream(coefficients)
            .map(BigInteger::abs)
            .mapToInt(BigInteger::intValueExact)
            .max()
            .orElse(0);
    }

    private int nodeCount(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            return 1 + nodeCount(binary.left()) + nodeCount(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            return 1 + function.arguments().stream()
                .mapToInt(this::nodeCount)
                .sum();
        }
        return 1;
    }

    private static void append(StringBuilder target, String value) {
        String safe = value == null ? "" : value;
        target.append(safe.length()).append(':').append(safe);
    }

    private static UnsupportedSubject unsupported(String detailCode) {
        return new UnsupportedSubject(detailCode);
    }

    private static BudgetExceeded budget(String detailCode) {
        return new BudgetExceeded(detailCode);
    }

    public enum Status {
        SYNTHESIZED,
        NO_DECOMPOSITION_FOUND,
        UNSUPPORTED,
        BUDGET_EXCEEDED
    }

    public record Budget(
        int maxTotalDegree,
        int maxFactorDegree,
        int maxCoefficientAbs,
        int maxEnumeratedTemplates,
        int maxCandidates
    ) {
        public static final Budget DEFAULT =
            new Budget(8, 4, 8, 100_000, 8);

        public Budget {
            if (maxTotalDegree < 2
                    || maxFactorDegree < 1
                    || maxCoefficientAbs < 1
                    || maxEnumeratedTemplates < 1
                    || maxCandidates < 1) {
                throw new IllegalArgumentException(
                    "polynomial decomposition budget is invalid");
            }
        }

        String canonicalMaterial() {
            return maxTotalDegree + ":"
                + maxFactorDegree + ":"
                + maxCoefficientAbs + ":"
                + maxEnumeratedTemplates + ":"
                + maxCandidates;
        }
    }

    public record Work(
        int enumeratedTemplates,
        int exactDivisions,
        int acceptedCandidates
    ) {
        public Work {
            if (enumeratedTemplates < 0
                    || exactDivisions < 0
                    || acceptedCandidates < 0) {
                throw new IllegalArgumentException("work must not be negative");
            }
        }

        String canonicalMaterial() {
            return enumeratedTemplates + ":"
                + exactDivisions + ":"
                + acceptedCandidates;
        }
    }

    public record Certificate(
        String algorithmId,
        String sourceSemanticHash,
        List<String> generatorKeys,
        String numericContent,
        String commonMonomial,
        int sourceDegree,
        List<String> factorCoefficients,
        List<String> quotientCoefficients,
        String method,
        Budget budget,
        Work work,
        String certificateHash
    ) {
        public Certificate {
            if (!ALGORITHM_ID.equals(algorithmId)
                    || sourceSemanticHash == null
                    || !sourceSemanticHash.matches("sha256:[0-9a-f]{64}")
                    || numericContent == null
                    || commonMonomial == null
                    || sourceDegree < 2
                    || method == null
                    || method.isBlank()
                    || budget == null
                    || work == null
                    || certificateHash == null
                    || !certificateHash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "polynomial synthesis certificate is invalid");
            }
            generatorKeys = List.copyOf(generatorKeys);
            factorCoefficients = List.copyOf(factorCoefficients);
            quotientCoefficients = List.copyOf(quotientCoefficients);
        }
    }

    public record Candidate(
        Expr factoredExpression,
        Expr firstFactor,
        Expr secondFactor,
        Certificate certificate,
        String canonicalPair,
        int score
    ) {
        public Candidate {
            Objects.requireNonNull(factoredExpression, "factoredExpression");
            Objects.requireNonNull(firstFactor, "firstFactor");
            Objects.requireNonNull(secondFactor, "secondFactor");
            Objects.requireNonNull(certificate, "certificate");
            if (canonicalPair == null || canonicalPair.isBlank() || score < 0) {
                throw new IllegalArgumentException("candidate identity is invalid");
            }
        }
    }

    public record Result(
        Status status,
        List<Candidate> candidates,
        Work work,
        String detailCode
    ) {
        public Result {
            Objects.requireNonNull(status, "status");
            candidates = List.copyOf(candidates);
            Objects.requireNonNull(work, "work");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException("detailCode is required");
            }
            if ((status == Status.SYNTHESIZED) != !candidates.isEmpty()) {
                throw new IllegalArgumentException(
                    "synthesis status and candidates must agree");
            }
        }

        public boolean synthesized() {
            return status == Status.SYNTHESIZED;
        }
    }

    private record Prepared(
        View view,
        List<Generator> generators,
        Polynomial primitive,
        Monomial commonMonomial,
        BigInteger numericContent,
        BigInteger[] coefficients,
        int degree,
        boolean implicitUnit
    ) {
        private Prepared {
            generators = List.copyOf(generators);
            coefficients = Arrays.copyOf(coefficients, coefficients.length);
        }

        @Override
        public BigInteger[] coefficients() {
            return Arrays.copyOf(coefficients, coefficients.length);
        }
    }

    private static final class MutableWork {
        private int enumeratedTemplates;
        private int exactDivisions;
        private int acceptedCandidates;

        private void enumerate(Budget budget) {
            enumeratedTemplates++;
            if (enumeratedTemplates > budget.maxEnumeratedTemplates()) {
                throw budget("MAX_ENUMERATED_TEMPLATES_EXCEEDED");
            }
        }

        private void divide() {
            exactDivisions++;
        }

        private void accept() {
            acceptedCandidates++;
        }

        private Work snapshot() {
            return new Work(
                enumeratedTemplates,
                exactDivisions,
                acceptedCandidates);
        }
    }

    private static final class UnsupportedSubject extends RuntimeException {
        private UnsupportedSubject(String detailCode) {
            super(detailCode);
        }
    }

    private static final class BudgetExceeded extends RuntimeException {
        private BudgetExceeded(String detailCode) {
            super(detailCode);
        }
    }
}
