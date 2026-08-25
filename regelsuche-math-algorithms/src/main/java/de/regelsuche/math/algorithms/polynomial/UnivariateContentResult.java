package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.Objects;

/**
 * Package-issued evidence for exact content and primitive-part normalization.
 *
 * <p>Public callers can inspect a result but cannot construct a successful
 * result. The algorithm package binds the complete factorization request,
 * growth policy, work and exact output into one deterministic certificate.</p>
 */
public final class UnivariateContentResult {
    private final State state;

    private UnivariateContentResult(
        Status status,
        String detailCode,
        String sourceDomainId,
        BigInteger denominatorClearingFactor,
        BigInteger integerContent,
        ExactRational scalar,
        SparsePolynomial<BigInteger> primitivePart,
        PolynomialWorkLedger work,
        String certificateHash
    ) {
        state = new State(
            status,
            detailCode,
            sourceDomainId,
            denominatorClearingFactor,
            integerContent,
            scalar,
            primitivePart,
            work,
            certificateHash);
    }

    static <C> UnivariateContentResult completed(
        BigInteger denominatorClearingFactor,
        BigInteger integerContent,
        ExactRational scalar,
        SparsePolynomial<BigInteger> primitivePart,
        PolynomialWorkLedger work,
        FactorizationRequest<C> request,
        UnivariateContentPolicy policy
    ) {
        return create(
            Status.COMPLETED,
            "CONTENT_AND_PRIMITIVE_PART_VERIFIED",
            denominatorClearingFactor,
            integerContent,
            scalar,
            primitivePart,
            work,
            request,
            policy);
    }

    static <C> UnivariateContentResult failure(
        Status status,
        String detailCode,
        PolynomialWorkLedger work,
        FactorizationRequest<C> request,
        UnivariateContentPolicy policy
    ) {
        if (status == Status.COMPLETED) {
            throw new IllegalArgumentException(
                "completed content normalization requires exact output");
        }
        return create(
            status,
            detailCode,
            null,
            null,
            null,
            null,
            work,
            request,
            policy);
    }

    private static <C> UnivariateContentResult create(
        Status status,
        String detailCode,
        BigInteger denominatorClearingFactor,
        BigInteger integerContent,
        ExactRational scalar,
        SparsePolynomial<BigInteger> primitivePart,
        PolynomialWorkLedger work,
        FactorizationRequest<C> request,
        UnivariateContentPolicy policy
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        String sourceDomainId = request.source()
            .ring()
            .coefficientDomain()
            .id();
        StringBuilder material = new StringBuilder(
            UnivariateContentNormalization.METHOD_ID);
        AlgorithmEvidence.append(material, sourceDomainId);
        AlgorithmEvidence.append(
            material,
            request.canonicalMaterial());
        AlgorithmEvidence.append(
            material,
            policy.canonicalMaterial());
        AlgorithmEvidence.append(material, status.name());
        AlgorithmEvidence.append(material, detailCode);
        AlgorithmEvidence.append(
            material,
            work.canonicalMaterial());
        AlgorithmEvidence.append(
            material,
            text(denominatorClearingFactor));
        AlgorithmEvidence.append(
            material,
            text(integerContent));
        AlgorithmEvidence.append(
            material,
            scalar == null ? "" : scalar.canonicalText());
        AlgorithmEvidence.append(
            material,
            primitivePart == null
                ? ""
                : primitivePart.canonicalMaterial());
        return new UnivariateContentResult(
            status,
            detailCode,
            sourceDomainId,
            denominatorClearingFactor,
            integerContent,
            scalar,
            primitivePart,
            work,
            AlgorithmEvidence.sha256(material.toString()));
    }

    private static String text(BigInteger value) {
        return value == null ? "" : value.toString();
    }

    public Status status() {
        return state.status();
    }

    public String detailCode() {
        return state.detailCode();
    }

    public String sourceDomainId() {
        return state.sourceDomainId();
    }

    public BigInteger denominatorClearingFactor() {
        requireCompleted();
        return state.denominatorClearingFactor();
    }

    public BigInteger integerContent() {
        requireCompleted();
        return state.integerContent();
    }

    public ExactRational scalar() {
        requireCompleted();
        return state.scalar();
    }

    public SparsePolynomial<BigInteger> primitivePart() {
        requireCompleted();
        return state.primitivePart();
    }

    public PolynomialWorkLedger work() {
        return state.work();
    }

    public String certificateHash() {
        return state.certificateHash();
    }

    public boolean completed() {
        return status() == Status.COMPLETED;
    }

    private void requireCompleted() {
        if (!completed()) {
            throw new IllegalStateException(
                "failed content normalization has no exact output");
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other
            || other instanceof UnivariateContentResult result
                && state.equals(result.state);
    }

    @Override
    public int hashCode() {
        return state.hashCode();
    }

    @Override
    public String toString() {
        return "UnivariateContentResult[" + state + ']';
    }

    public enum Status {
        COMPLETED,
        UNSUPPORTED_DOMAIN,
        UNSUPPORTED_SHAPE,
        BUDGET_INCONCLUSIVE,
        TECHNICAL_FAILURE
    }

    private record State(
        Status status,
        String detailCode,
        String sourceDomainId,
        BigInteger denominatorClearingFactor,
        BigInteger integerContent,
        ExactRational scalar,
        SparsePolynomial<BigInteger> primitivePart,
        PolynomialWorkLedger work,
        String certificateHash
    ) {
        private State {
            Objects.requireNonNull(status, "status");
            if (detailCode == null
                    || detailCode.isBlank()
                    || sourceDomainId == null
                    || sourceDomainId.isBlank()
                    || work == null
                    || certificateHash == null
                    || !certificateHash.matches(
                        "sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "univariate content result is invalid");
            }
            if (status == Status.COMPLETED) {
                validateCompleted(
                    denominatorClearingFactor,
                    integerContent,
                    scalar,
                    primitivePart);
            } else if (denominatorClearingFactor != null
                    || integerContent != null
                    || scalar != null
                    || primitivePart != null) {
                throw new IllegalArgumentException(
                    "failed normalization cannot expose exact output");
            }
        }

        private static void validateCompleted(
            BigInteger denominatorClearingFactor,
            BigInteger integerContent,
            ExactRational scalar,
            SparsePolynomial<BigInteger> primitivePart
        ) {
            Objects.requireNonNull(
                denominatorClearingFactor,
                "denominatorClearingFactor");
            Objects.requireNonNull(
                integerContent,
                "integerContent");
            Objects.requireNonNull(scalar, "scalar");
            Objects.requireNonNull(
                primitivePart,
                "primitivePart");
            if (denominatorClearingFactor.signum() <= 0
                    || integerContent.signum() <= 0
                    || scalar.signum() == 0
                    || primitivePart.isZero()
                    || primitivePart.leadingCoefficient()
                        .signum() <= 0
                    || !BigIntegerDomain.DOMAIN_ID.equals(
                        primitivePart.ring()
                            .coefficientDomain()
                            .id())
                    || !scalar.abs().equals(
                        new ExactRational(
                            integerContent,
                            denominatorClearingFactor))) {
                throw new IllegalArgumentException(
                    "completed normalization is not canonical");
            }
        }
    }
}
