package de.regelsuche.scalar;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Clears exact denominators and extracts integer content from a univariate
 * rational polynomial under explicit finite budgets.
 */
public final class ExactRationalPolynomialContentNormalizer {
    public static final String DOMAIN_ID =
        "regelsuche.exact-rational-polynomial-content/v1";
    public static final Budget DEFAULT_BUDGET =
        new Budget(32, 4_096, 100_000);

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
        WorkCounter work = new WorkCounter(budget.maxArithmeticSteps());

        if (polynomial.degree() > budget.maxDegree()) {
            return failure(
                Status.DEGREE_LIMIT_EXCEEDED,
                "RATIONAL_POLYNOMIAL_DEGREE_LIMIT_EXCEEDED",
                source,
                work.snapshot());
        }

        try {
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

            if (polynomial.isZero()) {
                return failure(
                    Status.ZERO_POLYNOMIAL,
                    "ZERO_POLYNOMIAL_HAS_NO_PRIMITIVE_CONTENT",
                    source,
                    work.snapshot());
            }

            BigInteger clearingFactor = BigInteger.ONE;
            for (ExactRational coefficient
                    : polynomial.coefficientsAscending()) {
                BigInteger denominator = coefficient.denominator();
                work.lcmOperation();
                work.gcdOperation();
                BigInteger divisor = clearingFactor.gcd(denominator);
                work.division();
                BigInteger reduced = clearingFactor.divide(divisor);
                work.multiplication();
                clearingFactor = reduced.multiply(denominator);
            }

            List<BigInteger> integral = new ArrayList<>(
                polynomial.coefficientsAscending().size());
            for (ExactRational coefficient
                    : polynomial.coefficientsAscending()) {
                work.division();
                BigInteger multiplier = clearingFactor.divide(
                    coefficient.denominator());
                work.multiplication();
                integral.add(coefficient.numerator().multiply(multiplier));
            }

            BigInteger content = BigInteger.ZERO;
            for (BigInteger coefficient : integral) {
                if (coefficient.signum() != 0) {
                    work.gcdOperation();
                    content = content.gcd(coefficient.abs());
                }
            }
            if (content.signum() == 0) {
                throw new IllegalStateException(
                    "nonzero rational polynomial produced zero content");
            }

            List<BigInteger> primitive = new ArrayList<>(integral.size());
            for (BigInteger coefficient : integral) {
                work.division();
                primitive.add(coefficient.divide(content));
            }
            ExactRational scalar = new ExactRational(
                content,
                clearingFactor);

            if (primitive.getLast().signum() < 0) {
                for (int index = 0; index < primitive.size(); index++) {
                    work.signAdjustment();
                    primitive.set(index, primitive.get(index).negate());
                }
                work.signAdjustment();
                scalar = scalar.negate();
            }

            for (int index = 0; index < primitive.size(); index++) {
                work.multiplication();
                ExactRational reconstructed = ExactRational.integer(
                    primitive.get(index)).multiply(scalar);
                work.reconstructionCheck();
                if (!reconstructed.equals(
                        polynomial.coefficientsAscending().get(index))) {
                    throw new IllegalStateException(
                        "rational polynomial content reconstruction failed");
                }
            }

            ExactRationalPolynomialContentEvidence.Normalization result =
                new ExactRationalPolynomialContentEvidence.Normalization(
                    clearingFactor,
                    integral,
                    content,
                    primitive,
                    scalar);
            ExactRationalPolynomialContentEvidence.WorkLedger ledger =
                work.snapshot();
            return ExactRationalPolynomialContentEvidence.normalized(
                source,
                result,
                ledger,
                certificate(
                    Status.NORMALIZED,
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
            work,
            certificate(status, source, null, work));
    }

    private static String certificate(
        Status status,
        List<String> source,
        ExactRationalPolynomialContentEvidence.Normalization result,
        ExactRationalPolynomialContentEvidence.WorkLedger work
    ) {
        StringBuilder material = new StringBuilder();
        append(material, DOMAIN_ID);
        append(material, status.name());
        append(material, Integer.toString(source.size()));
        source.forEach(value -> append(material, value));
        if (result != null) {
            append(
                material,
                result.denominatorClearingFactor().toString());
            append(material, result.integerContent().toString());
            append(material, result.scalar().canonicalText());
            append(
                material,
                Integer.toString(
                    result.integralCoefficientsAscending().size()));
            result.integralCoefficientsAscending().forEach(value ->
                append(material, value.toString()));
            result.primitiveCoefficientsAscending().forEach(value ->
                append(material, value.toString()));
        }
        append(material, work.toString());
        return hash(material.toString());
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String hash(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public enum Status {
        NORMALIZED,
        ZERO_POLYNOMIAL,
        DEGREE_LIMIT_EXCEEDED,
        COEFFICIENT_LIMIT_EXCEEDED,
        WORK_LIMIT_EXCEEDED
    }

    public record Budget(
        int maxDegree,
        int maxCoefficientBits,
        int maxArithmeticSteps
    ) {
        public Budget {
            if (maxDegree < 0
                    || maxCoefficientBits < 1
                    || maxArithmeticSteps < 1) {
                throw new IllegalArgumentException(
                    "rational polynomial content budget is invalid");
            }
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
