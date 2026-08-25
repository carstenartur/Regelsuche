package de.regelsuche.scalar;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Clears denominators and extracts exact integer polynomial content. */
public final class ExactRationalPolynomialContentNormalizer {
    public static final String DOMAIN_ID =
        "regelsuche.exact-rational-polynomial-content/v1";
    public static final int MAX_DEGREE = 64;
    public static final int MAX_COEFFICIENT_BITS = 8_192;
    public static final int MAX_INTERMEDIATE_BITS = 262_144;
    public static final int MAX_ARITHMETIC_STEPS = 500_000;
    public static final Budget DEFAULT_BUDGET =
        new Budget(32, 4_096, 131_072, 100_000);

    private final Budget budget;

    public ExactRationalPolynomialContentNormalizer() {
        this(DEFAULT_BUDGET);
    }

    public ExactRationalPolynomialContentNormalizer(Budget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public Budget budget() {
        return budget;
    }

    public ExactRationalPolynomialContentEvidence normalize(
        ExactRationalPolynomial polynomial
    ) {
        Objects.requireNonNull(polynomial, "polynomial");
        List<String> source = polynomial.coefficientsAscending().stream()
            .map(ExactRational::canonicalText)
            .toList();
        WorkCounter work = new WorkCounter(
            budget.maxArithmeticSteps());
        if (polynomial.degree() > budget.maxDegree()) {
            return failure(
                Status.DEGREE_LIMIT_EXCEEDED,
                "RATIONAL_POLYNOMIAL_DEGREE_LIMIT_EXCEEDED",
                source,
                work.snapshot());
        }

        try {
            ExactRationalPolynomialContentEvidence sourceFailure =
                validateSource(polynomial, source, work);
            if (sourceFailure != null) {
                return sourceFailure;
            }
            if (polynomial.isZero()) {
                return failure(
                    Status.ZERO_POLYNOMIAL,
                    "ZERO_POLYNOMIAL_HAS_NO_PRIMITIVE_CONTENT",
                    source,
                    work.snapshot());
            }

            BigInteger clearingFactor = denominatorLcm(
                polynomial,
                work);
            if (clearingFactor == null) {
                return intermediateFailure(
                    "DENOMINATOR_LCM_BIT_LIMIT_EXCEEDED",
                    source,
                    work);
            }
            List<BigInteger> integral = integralCoefficients(
                polynomial,
                clearingFactor,
                work);
            if (integral == null) {
                return intermediateFailure(
                    "INTEGRAL_COEFFICIENT_BIT_LIMIT_EXCEEDED",
                    source,
                    work);
            }
            BigInteger content = integerContent(integral, work);
            if (content.signum() == 0) {
                throw new IllegalStateException(
                    "nonzero polynomial produced zero content");
            }
            List<BigInteger> primitive = primitiveCoefficients(
                integral,
                content,
                work);
            if (primitive == null) {
                return intermediateFailure(
                    "PRIMITIVE_COEFFICIENT_BIT_LIMIT_EXCEEDED",
                    source,
                    work);
            }

            ExactRational scalar = new ExactRational(
                content,
                clearingFactor);
            boolean signMoved = primitive.getLast().signum() < 0;
            if (signMoved) {
                for (int index = 0; index < primitive.size(); index++) {
                    work.signAdjustment();
                    primitive.set(index, primitive.get(index).negate());
                }
                work.signAdjustment();
                scalar = scalar.negate();
            }

            validatePrimitiveGcd(primitive, work);
            validateIntegralReassembly(
                integral,
                primitive,
                content,
                signMoved,
                work);
            validateSourceReassembly(
                polynomial,
                primitive,
                scalar,
                work);

            var result = new ExactRationalPolynomialContentEvidence
                .Normalization(
                    clearingFactor,
                    integral,
                    content,
                    primitive,
                    scalar);
            var ledger = work.snapshot();
            return ExactRationalPolynomialContentEvidence.normalized(
                source,
                budget,
                result,
                ledger,
                certificate(
                    Status.NORMALIZED,
                    "RATIONAL_CONTENT_NORMALIZED_EXACTLY",
                    source,
                    result,
                    ledger));
        } catch (WorkLimitExceeded exception) {
            return failure(
                Status.WORK_LIMIT_EXCEEDED,
                "RATIONAL_CONTENT_WORK_LIMIT_EXCEEDED",
                source,
                work.snapshot());
        }
    }

    private ExactRationalPolynomialContentEvidence validateSource(
        ExactRationalPolynomial polynomial,
        List<String> source,
        WorkCounter work
    ) {
        for (ExactRational coefficient
                : polynomial.coefficientsAscending()) {
            work.visitCoefficient();
            if (coefficient.numerator().abs().bitLength()
                    > budget.maxCoefficientBits()
                    || coefficient.denominator().bitLength()
                    > budget.maxCoefficientBits()) {
                return failure(
                    Status.COEFFICIENT_LIMIT_EXCEEDED,
                    "RATIONAL_COEFFICIENT_BIT_LIMIT_EXCEEDED",
                    source,
                    work.snapshot());
            }
        }
        return null;
    }

    private BigInteger denominatorLcm(
        ExactRationalPolynomial polynomial,
        WorkCounter work
    ) {
        BigInteger result = BigInteger.ONE;
        for (ExactRational coefficient
                : polynomial.coefficientsAscending()) {
            BigInteger denominator = coefficient.denominator();
            work.lcmOperation();
            work.gcdOperation();
            BigInteger divisor = result.gcd(denominator);
            work.division();
            BigInteger reduced = result.divide(divisor);
            work.multiplication();
            if (productMayExceedLimit(reduced, denominator)) {
                return null;
            }
            result = reduced.multiply(denominator);
            if (!withinIntermediateLimit(result)) {
                return null;
            }
        }
        return result;
    }

    private List<BigInteger> integralCoefficients(
        ExactRationalPolynomial polynomial,
        BigInteger clearingFactor,
        WorkCounter work
    ) {
        List<BigInteger> result = new ArrayList<>(
            polynomial.coefficientsAscending().size());
        for (ExactRational coefficient
                : polynomial.coefficientsAscending()) {
            work.division();
            BigInteger multiplier = clearingFactor.divide(
                coefficient.denominator());
            work.multiplication();
            if (productMayExceedLimit(
                    coefficient.numerator(),
                    multiplier)) {
                return null;
            }
            BigInteger value = coefficient.numerator()
                .multiply(multiplier);
            if (!withinIntermediateLimit(value)) {
                return null;
            }
            result.add(value);
        }
        return result;
    }

    private BigInteger integerContent(
        List<BigInteger> integral,
        WorkCounter work
    ) {
        BigInteger content = BigInteger.ZERO;
        for (BigInteger coefficient : integral) {
            if (coefficient.signum() != 0) {
                work.gcdOperation();
                content = content.gcd(coefficient.abs());
            }
        }
        return content;
    }

    private List<BigInteger> primitiveCoefficients(
        List<BigInteger> integral,
        BigInteger content,
        WorkCounter work
    ) {
        List<BigInteger> result = new ArrayList<>(integral.size());
        for (BigInteger coefficient : integral) {
            work.division();
            BigInteger value = coefficient.divide(content);
            if (!withinIntermediateLimit(value)) {
                return null;
            }
            result.add(value);
        }
        return result;
    }

    private void validatePrimitiveGcd(
        List<BigInteger> primitive,
        WorkCounter work
    ) {
        BigInteger gcd = BigInteger.ZERO;
        for (BigInteger coefficient : primitive) {
            if (coefficient.signum() != 0) {
                work.gcdOperation();
                gcd = gcd.gcd(coefficient.abs());
            }
        }
        if (!BigInteger.ONE.equals(gcd)) {
            throw new IllegalStateException(
                "primitive coefficient validation failed");
        }
    }

    private void validateIntegralReassembly(
        List<BigInteger> integral,
        List<BigInteger> primitive,
        BigInteger content,
        boolean signMoved,
        WorkCounter work
    ) {
        BigInteger signedContent = signMoved
            ? content.negate()
            : content;
        if (signMoved) {
            work.signAdjustment();
        }
        for (int index = 0; index < primitive.size(); index++) {
            work.multiplication();
            BigInteger reconstructed = primitive.get(index)
                .multiply(signedContent);
            work.reconstructionCheck();
            if (!reconstructed.equals(integral.get(index))) {
                throw new IllegalStateException(
                    "integral content reconstruction failed");
            }
        }
    }

    private void validateSourceReassembly(
        ExactRationalPolynomial polynomial,
        List<BigInteger> primitive,
        ExactRational scalar,
        WorkCounter work
    ) {
        for (int index = 0; index < primitive.size(); index++) {
            work.multiplication();
            ExactRational reconstructed = ExactRational.integer(
                primitive.get(index)).multiply(scalar);
            work.reconstructionCheck();
            if (!reconstructed.equals(
                    polynomial.coefficientsAscending().get(index))) {
                throw new IllegalStateException(
                    "rational content reconstruction failed");
            }
        }
    }

    private boolean productMayExceedLimit(
        BigInteger left,
        BigInteger right
    ) {
        if (left.signum() == 0 || right.signum() == 0) {
            return false;
        }
        long upperBound = (long) left.abs().bitLength()
            + right.abs().bitLength();
        return upperBound > (long) budget.maxIntermediateBits() + 1L;
    }

    private boolean withinIntermediateLimit(BigInteger value) {
        return value.abs().bitLength()
            <= budget.maxIntermediateBits();
    }

    private ExactRationalPolynomialContentEvidence intermediateFailure(
        String detailCode,
        List<String> source,
        WorkCounter work
    ) {
        return failure(
            Status.INTERMEDIATE_LIMIT_EXCEEDED,
            detailCode,
            source,
            work.snapshot());
    }

    private ExactRationalPolynomialContentEvidence failure(
        Status status,
        String detailCode,
        List<String> source,
        ExactRationalPolynomialContentEvidence.WorkLedger work
    ) {
        return ExactRationalPolynomialContentEvidence.failure(
            status,
            detailCode,
            source,
            budget,
            work,
            certificate(
                status,
                detailCode,
                source,
                null,
                work));
    }

    private String certificate(
        Status status,
        String detailCode,
        List<String> source,
        ExactRationalPolynomialContentEvidence.Normalization result,
        ExactRationalPolynomialContentEvidence.WorkLedger work
    ) {
        StringBuilder material = new StringBuilder();
        append(material, DOMAIN_ID);
        append(material, status.name());
        append(material, detailCode);
        append(material, budget.canonicalMaterial());
        append(material, Integer.toString(source.size()));
        source.forEach(value -> append(material, value));
        if (result != null) {
            append(material, result.denominatorClearingFactor().toString());
            append(material, result.integerContent().toString());
            append(material, result.scalar().canonicalText());
            appendBigIntegers(
                material,
                result.integralCoefficientsAscending());
            appendBigIntegers(
                material,
                result.primitiveCoefficientsAscending());
        }
        append(material, work.canonicalMaterial());
        return hash(material.toString());
    }

    private static void appendBigIntegers(
        StringBuilder target,
        List<BigInteger> values
    ) {
        append(target, Integer.toString(values.size()));
        values.forEach(value -> append(target, value.toString()));
    }

    static void append(StringBuilder target, String value) {
        int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
        target.append(byteLength).append(':').append(value);
    }

    static String hash(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "SHA-256 unavailable",
                exception);
        }
    }

    public enum Status {
        NORMALIZED,
        ZERO_POLYNOMIAL,
        DEGREE_LIMIT_EXCEEDED,
        COEFFICIENT_LIMIT_EXCEEDED,
        INTERMEDIATE_LIMIT_EXCEEDED,
        WORK_LIMIT_EXCEEDED
    }

    public record Budget(
        int maxDegree,
        int maxCoefficientBits,
        int maxIntermediateBits,
        int maxArithmeticSteps
    ) {
        public Budget {
            if (maxDegree < 0
                    || maxDegree > MAX_DEGREE
                    || maxCoefficientBits < 1
                    || maxCoefficientBits > MAX_COEFFICIENT_BITS
                    || maxIntermediateBits < maxCoefficientBits
                    || maxIntermediateBits > MAX_INTERMEDIATE_BITS
                    || maxArithmeticSteps < 1
                    || maxArithmeticSteps > MAX_ARITHMETIC_STEPS) {
                throw new IllegalArgumentException(
                    "rational polynomial content budget is invalid");
            }
        }

        String canonicalMaterial() {
            return maxDegree + ":"
                + maxCoefficientBits + ":"
                + maxIntermediateBits + ":"
                + maxArithmeticSteps;
        }
    }

    private static final class WorkCounter {
        private final int maximum;
        private int coefficientsVisited;
        private int gcdOperations;
        private int lcmOperations;
        private int multiplications;
        private int divisions;
        private int signAdjustments;
        private int reconstructionChecks;

        private WorkCounter(int maximum) {
            this.maximum = maximum;
        }

        private void visitCoefficient() {
            increment(() -> coefficientsVisited++);
        }

        private void gcdOperation() {
            increment(() -> gcdOperations++);
        }

        private void lcmOperation() {
            increment(() -> lcmOperations++);
        }

        private void multiplication() {
            increment(() -> multiplications++);
        }

        private void division() {
            increment(() -> divisions++);
        }

        private void signAdjustment() {
            increment(() -> signAdjustments++);
        }

        private void reconstructionCheck() {
            increment(() -> reconstructionChecks++);
        }

        private void increment(Runnable operation) {
            if (totalSteps() >= maximum) {
                throw new WorkLimitExceeded();
            }
            operation.run();
        }

        private int totalSteps() {
            return coefficientsVisited
                + gcdOperations
                + lcmOperations
                + multiplications
                + divisions
                + signAdjustments
                + reconstructionChecks;
        }

        private ExactRationalPolynomialContentEvidence.WorkLedger snapshot() {
            return new ExactRationalPolynomialContentEvidence.WorkLedger(
                coefficientsVisited,
                gcdOperations,
                lcmOperations,
                multiplications,
                divisions,
                signAdjustments,
                reconstructionChecks,
                totalSteps());
        }
    }

    private static final class WorkLimitExceeded
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
