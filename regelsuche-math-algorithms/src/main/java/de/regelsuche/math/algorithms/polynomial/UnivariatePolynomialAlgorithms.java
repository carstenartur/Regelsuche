package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.CoefficientDomain;
import de.regelsuche.polynomial.ExactField;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.util.Objects;

/** Exact, budgeted primitive algorithms over canonical univariate polynomials. */
public final class UnivariatePolynomialAlgorithms {
    public static final String GCD_METHOD_ID =
        "regelsuche.univariate-polynomial-gcd/v1";

    private UnivariatePolynomialAlgorithms() {
    }

    public static <C> GcdResult<C> gcd(
        SparsePolynomial<C> left,
        SparsePolynomial<C> right,
        long maxWorkUnits
    ) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (!left.ring().equals(right.ring())) {
            return GcdResult.failure(
                Status.UNSUPPORTED_SHAPE,
                "POLYNOMIAL_RING_MISMATCH",
                PolynomialWorkLedger.empty(),
                left,
                right);
        }
        if (left.ring().variableCount() != 1) {
            return GcdResult.failure(
                Status.UNSUPPORTED_SHAPE,
                "REQUIRES_ONE_POLYNOMIAL_VARIABLE",
                PolynomialWorkLedger.empty(),
                left,
                right);
        }
        if (left.isZero() && right.isZero()) {
            return GcdResult.failure(
                Status.UNSUPPORTED_SHAPE,
                "GCD_ZERO_ZERO_UNDEFINED",
                PolynomialWorkLedger.empty(),
                left,
                right);
        }
        ExactField<C> field = exactField(left.ring());
        if (field == null) {
            return GcdResult.failure(
                Status.UNSUPPORTED_DOMAIN,
                "REQUIRES_EXACT_COEFFICIENT_FIELD",
                PolynomialWorkLedger.empty(),
                left,
                right);
        }

        PolynomialWorkBudget work =
            new PolynomialWorkBudget(maxWorkUnits);
        try {
            UnivariatePolynomialView<C> result = gcd(
                UnivariatePolynomialView.from(left),
                UnivariatePolynomialView.from(right),
                field,
                work,
                "gcd");
            return GcdResult.completed(
                result.toSparsePolynomial(),
                work.ledger(),
                left,
                right);
        } catch (PolynomialWorkBudget.LimitReached exception) {
            return GcdResult.failure(
                Status.BUDGET_INCONCLUSIVE,
                "GCD_WORK_BUDGET_EXCEEDED",
                work.ledger(),
                left,
                right);
        } catch (RuntimeException exception) {
            return GcdResult.failure(
                Status.TECHNICAL_FAILURE,
                "GCD_" + exception.getClass().getSimpleName()
                    .toUpperCase(java.util.Locale.ROOT),
                work.ledger(),
                left,
                right);
        }
    }

    static <C> UnivariatePolynomialView<C> gcd(
        UnivariatePolynomialView<C> left,
        UnivariatePolynomialView<C> right,
        ExactField<C> field,
        PolynomialWorkBudget work,
        String stagePrefix
    ) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(work, "work");
        if (!left.ring().equals(right.ring())) {
            throw new IllegalArgumentException(
                "polynomial gcd ring mismatch");
        }
        if (left.isZero() && right.isZero()) {
            throw new IllegalArgumentException(
                "gcd of two zero polynomials is undefined");
        }
        if (left.isZero()) {
            return right.monic(
                field,
                work,
                stagePrefix + ".normalize");
        }
        if (right.isZero()) {
            return left.monic(
                field,
                work,
                stagePrefix + ".normalize");
        }

        UnivariatePolynomialView<C> a = left;
        UnivariatePolynomialView<C> b = right;
        while (!b.isZero()) {
            work.consume(stagePrefix + ".iterations", 1);
            UnivariatePolynomialView.DivisionResult<C> division =
                a.divideAndRemainder(
                    b,
                    field,
                    work,
                    stagePrefix + ".division");
            a = b;
            b = division.remainder();
        }
        return a.monic(
            field,
            work,
            stagePrefix + ".normalize");
    }

    static <C> UnivariatePolynomialView<C> exactQuotient(
        UnivariatePolynomialView<C> dividend,
        UnivariatePolynomialView<C> divisor,
        ExactField<C> field,
        PolynomialWorkBudget work,
        String stagePrefix
    ) {
        return dividend.exactQuotient(
            divisor,
            field,
            work,
            stagePrefix);
    }

    @SuppressWarnings("unchecked")
    static <C> ExactField<C> exactField(
        PolynomialRing<C> ring
    ) {
        CoefficientDomain<C> domain = ring.coefficientDomain();
        return domain instanceof ExactField<?> field
            ? (ExactField<C>) field
            : null;
    }

    public enum Status {
        COMPLETED,
        UNSUPPORTED_DOMAIN,
        UNSUPPORTED_SHAPE,
        BUDGET_INCONCLUSIVE,
        TECHNICAL_FAILURE
    }

    /** Issuer-owned gcd result. */
    public static final class GcdResult<C> {
        private final State<C> state;

        private GcdResult(
            Status status,
            String detailCode,
            SparsePolynomial<C> gcd,
            PolynomialWorkLedger work,
            String certificateHash
        ) {
            state = new State<>(
                status,
                detailCode,
                gcd,
                work,
                certificateHash);
        }

        private static <C> GcdResult<C> completed(
            SparsePolynomial<C> gcd,
            PolynomialWorkLedger work,
            SparsePolynomial<C> left,
            SparsePolynomial<C> right
        ) {
            return create(
                Status.COMPLETED,
                "MONIC_EUCLIDEAN_GCD_COMPLETED",
                gcd,
                work,
                left,
                right);
        }

        private static <C> GcdResult<C> failure(
            Status status,
            String detailCode,
            PolynomialWorkLedger work,
            SparsePolynomial<C> left,
            SparsePolynomial<C> right
        ) {
            if (status == Status.COMPLETED) {
                throw new IllegalArgumentException(
                    "completed gcd requires a result");
            }
            return create(
                status,
                detailCode,
                null,
                work,
                left,
                right);
        }

        private static <C> GcdResult<C> create(
            Status status,
            String detailCode,
            SparsePolynomial<C> gcd,
            PolynomialWorkLedger work,
            SparsePolynomial<C> left,
            SparsePolynomial<C> right
        ) {
            StringBuilder material = new StringBuilder(GCD_METHOD_ID);
            AlgorithmEvidence.append(
                material,
                left.canonicalMaterial());
            AlgorithmEvidence.append(
                material,
                right.canonicalMaterial());
            AlgorithmEvidence.append(material, status.name());
            AlgorithmEvidence.append(material, detailCode);
            AlgorithmEvidence.append(
                material,
                work.canonicalMaterial());
            AlgorithmEvidence.append(
                material,
                gcd == null ? "" : gcd.canonicalMaterial());
            return new GcdResult<>(
                status,
                detailCode,
                gcd,
                work,
                AlgorithmEvidence.sha256(material.toString()));
        }

        public Status status() {
            return state.status();
        }

        public String detailCode() {
            return state.detailCode();
        }

        public SparsePolynomial<C> gcd() {
            if (!completed()) {
                throw new IllegalStateException(
                    "failed gcd result has no polynomial");
            }
            return state.gcd();
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

        @Override
        public boolean equals(Object other) {
            return this == other
                || other instanceof GcdResult<?> result
                    && state.equals(result.state);
        }

        @Override
        public int hashCode() {
            return state.hashCode();
        }

        @Override
        public String toString() {
            return "GcdResult[" + state + ']';
        }

        private record State<C>(
            Status status,
            String detailCode,
            SparsePolynomial<C> gcd,
            PolynomialWorkLedger work,
            String certificateHash
        ) {
            private State {
                Objects.requireNonNull(status, "status");
                if (detailCode == null
                        || detailCode.isBlank()
                        || work == null
                        || certificateHash == null
                        || !certificateHash.matches(
                            "sha256:[0-9a-f]{64}")) {
                    throw new IllegalArgumentException(
                        "gcd result is invalid");
                }
                if ((status == Status.COMPLETED) != (gcd != null)) {
                    throw new IllegalArgumentException(
                        "gcd status/result mismatch");
                }
            }
        }
    }
}
