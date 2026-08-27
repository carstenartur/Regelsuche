package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.SparsePolynomial;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Factors one primitive square-free integer layer under a shared authority. */
final class NativeSquareFreeLayerFactorization {
    private NativeSquareFreeLayerFactorization() {
    }

    static Result factor(
        SparsePolynomial<BigInteger> source,
        FactorizationRequest.StructuralLimits structuralLimits,
        long maxWorkUnits,
        NativeUnivariateFactorizationPolicy policy,
        int candidateBudget,
        PolynomialWorkBudget work
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(structuralLimits, "structuralLimits");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(work, "work");
        if (candidateBudget < 1) {
            return Result.failure(
                FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE,
                "NATIVE_UNIVARIATE_CANDIDATE_BUDGET_EXHAUSTED",
                List.of());
        }

        FactorizationRequest<BigInteger> request =
            new FactorizationRequest<>(
                source,
                FactorizationRequest.EvidenceRequirement
                    .VERIFIED_DECOMPOSITION,
                structuralLimits,
                candidateBudget,
                maxWorkUnits);
        ArrayList<String> certificates = new ArrayList<>();

        BigInteger bound =
            IntegerPolynomialArithmetic.coefficientBound(
                source,
                policy.recombinationPolicy(),
                work);
        SuitablePrimeSelectionResult selection =
            SuitablePrimeSelection.selectAndFactor(
                request,
                policy.suitablePrimePolicy(),
                work);
        certificates.add(selection.certificateHash());
        if (!selection.completed()) {
            return Result.failure(
                map(selection.status()),
                selection.detailCode(),
                certificates);
        }

        int exponent =
            IntegerPolynomialArithmetic.minimumHenselExponent(
                selection.selectedPrime(),
                bound,
                policy.recombinationPolicy());
        HenselLiftingPolicy liftingPolicy =
            HenselLiftingPolicy.linearMultifactor(
                exponent,
                policy.recombinationPolicy().maxModulusBitLength(),
                policy.recombinationPolicy()
                    .maxIntermediateCoefficientBitLength());
        HenselLiftingResult lifting = HenselLifting.lift(
            request,
            selection,
            liftingPolicy,
            work);
        certificates.add(lifting.certificateHash());
        if (!lifting.completed()) {
            return Result.failure(
                map(lifting.status()),
                lifting.detailCode(),
                certificates);
        }

        ZassenhausRecombinationResult recombination =
            ZassenhausRecombination.recombine(
                request,
                selection,
                lifting,
                policy.recombinationPolicy(),
                bound,
                work);
        certificates.add(recombination.certificateHash());
        if (!recombination.completed()) {
            return Result.failure(
                map(recombination.status()),
                recombination.detailCode(),
                certificates);
        }

        long used = Math.addExact(
            selection.attempts().size(),
            recombination.candidatesConsidered());
        if (used > candidateBudget || used > Integer.MAX_VALUE) {
            return Result.failure(
                FactorizationEngine.Outcome.TECHNICAL_FAILURE,
                "NATIVE_UNIVARIATE_CANDIDATE_ACCOUNTING_MISMATCH",
                certificates);
        }
        return Result.completed(
            recombination.factors(),
            (int) used,
            certificates);
    }

    private static FactorizationEngine.Outcome map(
        SuitablePrimeSelectionResult.Status status
    ) {
        return switch (status) {
            case COMPLETED -> throw new IllegalArgumentException(
                "completed status cannot map to failure");
            case UNSUPPORTED_DOMAIN ->
                FactorizationEngine.Outcome.UNSUPPORTED_DOMAIN;
            case UNSUPPORTED_SHAPE ->
                FactorizationEngine.Outcome.UNSUPPORTED_REQUEST;
            case BUDGET_INCONCLUSIVE ->
                FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE;
            case TECHNICAL_FAILURE ->
                FactorizationEngine.Outcome.TECHNICAL_FAILURE;
        };
    }

    private static FactorizationEngine.Outcome map(
        HenselLiftingResult.Status status
    ) {
        return switch (status) {
            case COMPLETED -> throw new IllegalArgumentException(
                "completed status cannot map to failure");
            case UNSUPPORTED_DOMAIN ->
                FactorizationEngine.Outcome.UNSUPPORTED_DOMAIN;
            case UNSUPPORTED_SHAPE ->
                FactorizationEngine.Outcome.UNSUPPORTED_REQUEST;
            case BUDGET_INCONCLUSIVE ->
                FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE;
            case TECHNICAL_FAILURE ->
                FactorizationEngine.Outcome.TECHNICAL_FAILURE;
        };
    }

    private static FactorizationEngine.Outcome map(
        ZassenhausRecombinationResult.Status status
    ) {
        return switch (status) {
            case COMPLETED -> throw new IllegalArgumentException(
                "completed status cannot map to failure");
            case UNSUPPORTED_DOMAIN ->
                FactorizationEngine.Outcome.UNSUPPORTED_DOMAIN;
            case UNSUPPORTED_SHAPE ->
                FactorizationEngine.Outcome.UNSUPPORTED_REQUEST;
            case BUDGET_INCONCLUSIVE ->
                FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE;
            case TECHNICAL_FAILURE ->
                FactorizationEngine.Outcome.TECHNICAL_FAILURE;
        };
    }

    record Result(
        boolean completed,
        FactorizationEngine.Outcome outcome,
        String detailCode,
        List<SparsePolynomial<BigInteger>> factors,
        int candidatesUsed,
        List<String> certificates
    ) {
        Result {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(detailCode, "detailCode");
            factors = List.copyOf(factors);
            certificates = List.copyOf(certificates);
            if (candidatesUsed < 0
                    || completed != !factors.isEmpty()) {
                throw new IllegalArgumentException(
                    "native layer result is invalid");
            }
        }

        static Result completed(
            List<SparsePolynomial<BigInteger>> factors,
            int candidatesUsed,
            List<String> certificates
        ) {
            return new Result(
                true,
                FactorizationEngine.Outcome.CANDIDATES,
                "NATIVE_SQUARE_FREE_LAYER_COMPLETE",
                factors,
                candidatesUsed,
                certificates);
        }

        static Result failure(
            FactorizationEngine.Outcome outcome,
            String detailCode,
            List<String> certificates
        ) {
            return new Result(
                false,
                outcome,
                detailCode,
                List.of(),
                0,
                certificates);
        }
    }
}
