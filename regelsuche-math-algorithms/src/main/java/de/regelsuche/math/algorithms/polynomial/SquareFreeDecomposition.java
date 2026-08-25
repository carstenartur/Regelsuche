package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.CoefficientDomain;
import de.regelsuche.polynomial.ExactField;
import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exact characteristic-zero square-free decomposition with multiplicities.
 *
 * <p>This stage separates repeated factors. It does not claim that the
 * square-free factors are irreducible.</p>
 */
public final class SquareFreeDecomposition {
    public static final String METHOD_ID =
        "regelsuche.square-free-decomposition.char-zero/v1";

    private SquareFreeDecomposition() {
    }

    public static <C> Result<C> decompose(
        SparsePolynomial<C> source,
        FactorizationRequest.StructuralLimits structuralLimits,
        long maxWorkUnits
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(
            structuralLimits,
            "structuralLimits");
        String violation = structuralLimits.firstViolation(source)
            .orElse(null);
        if (violation != null) {
            return Result.failure(
                Status.BUDGET_INCONCLUSIVE,
                violation,
                FactorizationEngine.WorkLedger.empty(),
                source,
                structuralLimits);
        }
        if (source.ring().variableCount() != 1
                || source.isConstant()) {
            return Result.failure(
                Status.UNSUPPORTED_SHAPE,
                "REQUIRES_NONCONSTANT_UNIVARIATE_POLYNOMIAL",
                FactorizationEngine.WorkLedger.empty(),
                source,
                structuralLimits);
        }
        if (source.ring().coefficientDomain()
                .characteristic().signum() != 0) {
            return Result.failure(
                Status.UNSUPPORTED_DOMAIN,
                "REQUIRES_CHARACTERISTIC_ZERO",
                FactorizationEngine.WorkLedger.empty(),
                source,
                structuralLimits);
        }
        ExactField<C> field =
            UnivariatePolynomialAlgorithms.exactField(source.ring());
        if (field == null) {
            return Result.failure(
                Status.UNSUPPORTED_DOMAIN,
                "REQUIRES_EXACT_COEFFICIENT_FIELD",
                FactorizationEngine.WorkLedger.empty(),
                source,
                structuralLimits);
        }

        PolynomialWorkBudget work =
            new PolynomialWorkBudget(maxWorkUnits);
        try {
            return completed(
                source,
                structuralLimits,
                field,
                work);
        } catch (PolynomialWorkBudget.LimitReached exception) {
            return Result.failure(
                Status.BUDGET_INCONCLUSIVE,
                "SQUARE_FREE_WORK_BUDGET_EXCEEDED",
                work.ledger(),
                source,
                structuralLimits);
        } catch (ArithmeticException exception) {
            return Result.failure(
                Status.TECHNICAL_FAILURE,
                "SQUARE_FREE_EXACT_DIVISION_FAILED",
                work.ledger(),
                source,
                structuralLimits);
        } catch (RuntimeException exception) {
            return Result.failure(
                Status.TECHNICAL_FAILURE,
                "SQUARE_FREE_"
                    + exception.getClass().getSimpleName()
                        .toUpperCase(java.util.Locale.ROOT),
                work.ledger(),
                source,
                structuralLimits);
        }
    }

    private static <C> Result<C> completed(
        SparsePolynomial<C> source,
        FactorizationRequest.StructuralLimits structuralLimits,
        ExactField<C> field,
        PolynomialWorkBudget work
    ) {
        UnivariatePolynomialView<C> original =
            UnivariatePolynomialView.from(source);
        C unit = original.leadingCoefficient();
        work.consume("square-free.leading-normalization", 1);
        UnivariatePolynomialView<C> monic = original.scale(
            field.divide(field.one(), unit),
            work,
            "square-free.leading-normalization");
        UnivariatePolynomialView<C> derivative = monic.derivative(
            work,
            "square-free.derivative");

        UnivariatePolynomialView<C> repeated =
            UnivariatePolynomialAlgorithms.gcd(
                monic,
                derivative,
                field,
                work,
                "square-free.initial-gcd");
        UnivariatePolynomialView<C> current =
            UnivariatePolynomialAlgorithms.exactQuotient(
                monic,
                repeated,
                field,
                work,
                "square-free.initial-quotient");

        List<PolynomialFactor<C>> factors = new ArrayList<>();
        int multiplicity = 1;
        int maximumMultiplicity = monic.degree();
        while (!current.isOne()) {
            if (multiplicity > maximumMultiplicity) {
                throw new IllegalStateException(
                    "square-free multiplicity exceeded source degree");
            }
            UnivariatePolynomialView<C> shared =
                UnivariatePolynomialAlgorithms.gcd(
                    current,
                    repeated,
                    field,
                    work,
                    "square-free.layer-" + multiplicity + ".gcd");
            UnivariatePolynomialView<C> layer =
                UnivariatePolynomialAlgorithms.exactQuotient(
                    current,
                    shared,
                    field,
                    work,
                    "square-free.layer-" + multiplicity + ".factor");
            if (!layer.isOne()) {
                factors.add(new PolynomialFactor<>(
                    layer.toSparsePolynomial(),
                    multiplicity));
            }
            current = shared;
            repeated = UnivariatePolynomialAlgorithms.exactQuotient(
                repeated,
                shared,
                field,
                work,
                "square-free.layer-" + multiplicity + ".remainder");
            multiplicity++;
        }
        if (!repeated.isOne()) {
            throw new IllegalStateException(
                "characteristic-zero square-free remainder is not one");
        }
        if (factors.isEmpty()) {
            throw new IllegalStateException(
                "nonconstant polynomial produced no square-free factor");
        }

        verifyReconstruction(
            original,
            unit,
            factors,
            field,
            work);
        return Result.completed(
            unit,
            factors,
            work.ledger(),
            source,
            structuralLimits);
    }

    private static <C> void verifyReconstruction(
        UnivariatePolynomialView<C> source,
        C unit,
        List<PolynomialFactor<C>> factors,
        ExactField<C> field,
        PolynomialWorkBudget work
    ) {
        UnivariatePolynomialView<C> reconstructed =
            UnivariatePolynomialView.of(
                source.ring(),
                List.of(unit));
        for (PolynomialFactor<C> factor : factors) {
            UnivariatePolynomialView<C> powered = power(
                UnivariatePolynomialView.from(factor.polynomial()),
                factor.multiplicity(),
                work,
                "square-free.verify.factor-power");
            reconstructed = reconstructed.multiply(
                powered,
                work,
                "square-free.verify.product");
        }
        work.consume("square-free.verify.comparison", 1);
        if (!source.equals(reconstructed)) {
            throw new IllegalStateException(
                "square-free factors do not reconstruct the source");
        }
        for (PolynomialFactor<C> factor : factors) {
            UnivariatePolynomialView<C> polynomial =
                UnivariatePolynomialView.from(factor.polynomial());
            UnivariatePolynomialView<C> gcd =
                UnivariatePolynomialAlgorithms.gcd(
                    polynomial,
                    polynomial.derivative(
                        work,
                        "square-free.verify.derivative"),
                    field,
                    work,
                    "square-free.verify.gcd");
            work.consume("square-free.verify.square-free-comparison", 1);
            if (!gcd.isOne()) {
                throw new IllegalStateException(
                    "reported factor is not square-free");
            }
        }
    }

    private static <C> UnivariatePolynomialView<C> power(
        UnivariatePolynomialView<C> base,
        int exponent,
        PolynomialWorkBudget work,
        String stage
    ) {
        UnivariatePolynomialView<C> result =
            UnivariatePolynomialView.one(base.ring());
        UnivariatePolynomialView<C> factor = base;
        int remaining = exponent;
        while (remaining > 0) {
            if ((remaining & 1) == 1) {
                result = result.multiply(
                    factor,
                    work,
                    stage + ".multiply");
            }
            remaining >>>= 1;
            if (remaining > 0) {
                factor = factor.multiply(
                    factor,
                    work,
                    stage + ".square");
            }
        }
        return result;
    }

    public enum Status {
        COMPLETED,
        UNSUPPORTED_DOMAIN,
        UNSUPPORTED_SHAPE,
        BUDGET_INCONCLUSIVE,
        TECHNICAL_FAILURE
    }

    /** Issuer-owned exact square-free decomposition evidence. */
    public static final class Result<C> {
        private final State<C> state;

        private Result(
            Status status,
            String detailCode,
            C unit,
            List<PolynomialFactor<C>> factors,
            FactorizationEngine.WorkLedger work,
            String certificateHash
        ) {
            state = new State<>(
                status,
                detailCode,
                unit,
                factors,
                work,
                certificateHash);
        }

        private static <C> Result<C> completed(
            C unit,
            List<PolynomialFactor<C>> factors,
            FactorizationEngine.WorkLedger work,
            SparsePolynomial<C> source,
            FactorizationRequest.StructuralLimits limits
        ) {
            return create(
                Status.COMPLETED,
                "SQUARE_FREE_DECOMPOSITION_VERIFIED",
                unit,
                factors,
                work,
                source,
                limits);
        }

        private static <C> Result<C> failure(
            Status status,
            String detailCode,
            FactorizationEngine.WorkLedger work,
            SparsePolynomial<C> source,
            FactorizationRequest.StructuralLimits limits
        ) {
            if (status == Status.COMPLETED) {
                throw new IllegalArgumentException(
                    "completed status requires decomposition");
            }
            return create(
                status,
                detailCode,
                null,
                List.of(),
                work,
                source,
                limits);
        }

        private static <C> Result<C> create(
            Status status,
            String detailCode,
            C unit,
            List<PolynomialFactor<C>> factors,
            FactorizationEngine.WorkLedger work,
            SparsePolynomial<C> source,
            FactorizationRequest.StructuralLimits limits
        ) {
            StringBuilder material = new StringBuilder(METHOD_ID);
            AlgorithmEvidence.append(
                material,
                source.canonicalMaterial());
            AlgorithmEvidence.append(
                material,
                limits.canonicalMaterial());
            AlgorithmEvidence.append(material, status.name());
            AlgorithmEvidence.append(material, detailCode);
            AlgorithmEvidence.append(
                material,
                work.canonicalMaterial());
            CoefficientDomain<C> domain =
                source.ring().coefficientDomain();
            AlgorithmEvidence.append(
                material,
                unit == null ? "" : domain.canonicalText(unit));
            factors.forEach(factor -> {
                AlgorithmEvidence.append(
                    material,
                    Integer.toString(factor.multiplicity()));
                AlgorithmEvidence.append(
                    material,
                    factor.polynomial().canonicalMaterial());
            });
            return new Result<>(
                status,
                detailCode,
                unit,
                factors,
                work,
                AlgorithmEvidence.sha256(material.toString()));
        }

        public Status status() {
            return state.status();
        }

        public String detailCode() {
            return state.detailCode();
        }

        public C unit() {
            requireCompleted();
            return state.unit();
        }

        public List<PolynomialFactor<C>> factors() {
            return state.factors();
        }

        public FactorizationEngine.WorkLedger work() {
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
                    "failed square-free result has no unit");
            }
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                || other instanceof Result<?> result
                    && state.equals(result.state);
        }

        @Override
        public int hashCode() {
            return state.hashCode();
        }

        @Override
        public String toString() {
            return "SquareFreeResult[" + state + ']';
        }

        private record State<C>(
            Status status,
            String detailCode,
            C unit,
            List<PolynomialFactor<C>> factors,
            FactorizationEngine.WorkLedger work,
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
                        "square-free result is invalid");
                }
                factors = List.copyOf(factors);
                if (status == Status.COMPLETED) {
                    Objects.requireNonNull(unit, "unit");
                    if (factors.isEmpty()) {
                        throw new IllegalArgumentException(
                            "completed square-free result requires factors");
                    }
                } else if (unit != null || !factors.isEmpty()) {
                    throw new IllegalArgumentException(
                        "failed square-free result cannot expose factors");
                }
            }
        }
    }
}
