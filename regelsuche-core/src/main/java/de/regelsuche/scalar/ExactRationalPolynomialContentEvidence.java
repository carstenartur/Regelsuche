package de.regelsuche.scalar;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Issuer-owned result of exact rational polynomial content normalization.
 */
public final class ExactRationalPolynomialContentEvidence {
    private final String domainId;
    private final ExactRationalPolynomialContentNormalizer.Status status;
    private final String detailCode;
    private final List<String> sourceCoefficients;
    private final Optional<Normalization> normalization;
    private final WorkLedger work;
    private final String certificateHash;

    ExactRationalPolynomialContentEvidence(
        String domainId,
        ExactRationalPolynomialContentNormalizer.Status status,
        String detailCode,
        List<String> sourceCoefficients,
        Optional<Normalization> normalization,
        WorkLedger work,
        String certificateHash
    ) {
        this.domainId = Objects.requireNonNull(domainId, "domainId");
        this.status = Objects.requireNonNull(status, "status");
        this.detailCode = requireText(detailCode, "detailCode");
        this.sourceCoefficients = List.copyOf(
            Objects.requireNonNull(
                sourceCoefficients,
                "sourceCoefficients"));
        this.normalization = Objects.requireNonNull(
            normalization,
            "normalization");
        this.work = Objects.requireNonNull(work, "work");
        this.certificateHash = requireHash(
            certificateHash,
            "certificateHash");
        validate();
    }

    static ExactRationalPolynomialContentEvidence normalized(
        List<String> sourceCoefficients,
        Normalization normalization,
        WorkLedger work,
        String certificateHash
    ) {
        return new ExactRationalPolynomialContentEvidence(
            ExactRationalPolynomialContentNormalizer.DOMAIN_ID,
            ExactRationalPolynomialContentNormalizer.Status.NORMALIZED,
            "RATIONAL_CONTENT_NORMALIZED_EXACTLY",
            sourceCoefficients,
            Optional.of(normalization),
            work,
            certificateHash);
    }

    static ExactRationalPolynomialContentEvidence failure(
        ExactRationalPolynomialContentNormalizer.Status status,
        String detailCode,
        List<String> sourceCoefficients,
        WorkLedger work,
        String certificateHash
    ) {
        if (status
                == ExactRationalPolynomialContentNormalizer.Status.NORMALIZED) {
            throw new IllegalArgumentException(
                "failure evidence cannot use NORMALIZED status");
        }
        return new ExactRationalPolynomialContentEvidence(
            ExactRationalPolynomialContentNormalizer.DOMAIN_ID,
            status,
            detailCode,
            sourceCoefficients,
            Optional.empty(),
            work,
            certificateHash);
    }

    private void validate() {
        if (!ExactRationalPolynomialContentNormalizer.DOMAIN_ID.equals(
                domainId)) {
            throw new IllegalArgumentException(
                "unexpected rational polynomial content domain id");
        }
        if (sourceCoefficients.isEmpty()) {
            throw new IllegalArgumentException(
                "content evidence requires source coefficients");
        }
        sourceCoefficients.forEach(value -> requireText(
            value,
            "source coefficient"));
        if (status
                == ExactRationalPolynomialContentNormalizer.Status.NORMALIZED) {
            normalization.orElseThrow(() ->
                new IllegalArgumentException(
                    "normalized evidence lacks normalization data"));
        } else if (normalization.isPresent()) {
            throw new IllegalArgumentException(
                "failed normalization must not expose a result");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requireHash(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
        return value;
    }

    public String domainId() {
        return domainId;
    }

    public ExactRationalPolynomialContentNormalizer.Status status() {
        return status;
    }

    public String detailCode() {
        return detailCode;
    }

    public List<String> sourceCoefficients() {
        return sourceCoefficients;
    }

    public Optional<Normalization> normalization() {
        return normalization;
    }

    public WorkLedger work() {
        return work;
    }

    public String certificateHash() {
        return certificateHash;
    }

    public boolean normalized() {
        return status
            == ExactRationalPolynomialContentNormalizer.Status.NORMALIZED;
    }

    public record Normalization(
        BigInteger denominatorClearingFactor,
        List<BigInteger> integralCoefficientsAscending,
        BigInteger integerContent,
        List<BigInteger> primitiveCoefficientsAscending,
        ExactRational scalar
    ) {
        public Normalization {
            Objects.requireNonNull(
                denominatorClearingFactor,
                "denominatorClearingFactor");
            integralCoefficientsAscending = List.copyOf(
                Objects.requireNonNull(
                    integralCoefficientsAscending,
                    "integralCoefficientsAscending"));
            Objects.requireNonNull(integerContent, "integerContent");
            primitiveCoefficientsAscending = List.copyOf(
                Objects.requireNonNull(
                    primitiveCoefficientsAscending,
                    "primitiveCoefficientsAscending"));
            Objects.requireNonNull(scalar, "scalar");
            if (denominatorClearingFactor.signum() <= 0
                    || integerContent.signum() <= 0
                    || integralCoefficientsAscending.isEmpty()
                    || primitiveCoefficientsAscending.isEmpty()
                    || integralCoefficientsAscending.size()
                        != primitiveCoefficientsAscending.size()
                    || primitiveCoefficientsAscending.getLast().signum() <= 0
                    || scalar.isZero()) {
                throw new IllegalArgumentException(
                    "rational polynomial normalization is invalid");
            }
            integralCoefficientsAscending.forEach(value ->
                Objects.requireNonNull(value, "integral coefficient"));
            primitiveCoefficientsAscending.forEach(value ->
                Objects.requireNonNull(value, "primitive coefficient"));
        }
    }

    public record WorkLedger(
        int coefficientsVisited,
        int gcdOperations,
        int lcmOperations,
        int multiplications,
        int divisions,
        int signAdjustments,
        int reconstructionChecks,
        int totalSteps
    ) {
        public WorkLedger {
            if (coefficientsVisited < 0
                    || gcdOperations < 0
                    || lcmOperations < 0
                    || multiplications < 0
                    || divisions < 0
                    || signAdjustments < 0
                    || reconstructionChecks < 0
                    || totalSteps < 0
                    || totalSteps != coefficientsVisited
                        + gcdOperations
                        + lcmOperations
                        + multiplications
                        + divisions
                        + signAdjustments
                        + reconstructionChecks) {
                throw new IllegalArgumentException(
                    "rational polynomial work ledger is inconsistent");
            }
        }

        public static WorkLedger zero() {
            return new WorkLedger(0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
