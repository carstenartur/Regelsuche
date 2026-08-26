package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.SparsePolynomial;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Issuer-owned evidence for one bounded multifactor Hensel lift. */
public final class HenselLiftingResult {
    private final State state;

    private HenselLiftingResult(State state) {
        this.state = state;
    }

    static HenselLiftingResult completed(
        BigInteger targetModulus,
        List<SparsePolynomial<BigInteger>> factors,
        List<HenselLiftStep> steps,
        PolynomialWorkLedger work,
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingPolicy policy
    ) {
        return create(
            Status.COMPLETED,
            "HENSEL_CONGRUENCE_VERIFIED_TO_TARGET_MODULUS",
            targetModulus,
            factors,
            steps,
            work,
            request,
            selection,
            policy);
    }

    static HenselLiftingResult failure(
        Status status,
        String detailCode,
        List<HenselLiftStep> steps,
        PolynomialWorkLedger work,
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingPolicy policy
    ) {
        if (status == Status.COMPLETED) {
            throw new IllegalArgumentException(
                "completed Hensel lift requires lifted factors");
        }
        return create(
            status,
            detailCode,
            null,
            List.of(),
            steps,
            work,
            request,
            selection,
            policy);
    }

    private static HenselLiftingResult create(
        Status status,
        String detailCode,
        BigInteger targetModulus,
        List<SparsePolynomial<BigInteger>> factors,
        List<HenselLiftStep> steps,
        PolynomialWorkLedger work,
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingPolicy policy
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(work, "work");
        List<SparsePolynomial<BigInteger>> retainedFactors =
            List.copyOf(factors);
        List<HenselLiftStep> retainedSteps = List.copyOf(steps);
        int prime = selection.completed()
            ? selection.selectedPrime()
            : 0;
        HenselLiftingEvidence.validateStepSequence(
            retainedSteps,
            prime,
            selection.completed()
                ? selection.modularFactorization().factors().size()
                : 0);
        if (retainedSteps.size() > policy.targetExponent() - 1) {
            throw new IllegalArgumentException(
                "Hensel steps exceed the target exponent");
        }
        if (status == Status.COMPLETED) {
            Objects.requireNonNull(targetModulus, "targetModulus");
            HenselLiftingEvidence.validateCompleted(
                targetModulus,
                retainedFactors,
                retainedSteps,
                work,
                request,
                selection,
                policy);
        } else if (targetModulus != null
                || !retainedFactors.isEmpty()) {
            throw new IllegalArgumentException(
                "failed Hensel lift cannot expose completed factors");
        }

        StringBuilder material = new StringBuilder(
            HenselLifting.METHOD_ID);
        AlgorithmEvidence.append(
            material,
            request.canonicalMaterial());
        AlgorithmEvidence.append(
            material,
            selection.certificateHash());
        AlgorithmEvidence.append(
            material,
            policy.canonicalMaterial());
        AlgorithmEvidence.append(material, status.name());
        AlgorithmEvidence.append(material, detailCode);
        AlgorithmEvidence.append(material, Integer.toString(prime));
        AlgorithmEvidence.append(
            material,
            work.canonicalMaterial());
        retainedSteps.forEach(step -> AlgorithmEvidence.append(
            material,
            step.canonicalMaterial()));
        AlgorithmEvidence.append(
            material,
            targetModulus == null ? "" : targetModulus.toString());
        retainedFactors.forEach(factor -> AlgorithmEvidence.append(
            material,
            factor.canonicalMaterial()));

        return new HenselLiftingResult(new State(
            status,
            detailCode,
            prime,
            targetModulus,
            retainedFactors,
            retainedSteps,
            work,
            selection.certificateHash(),
            AlgorithmEvidence.sha256(material.toString())));
    }

    public Status status() {
        return state.status();
    }

    public String detailCode() {
        return state.detailCode();
    }

    public int prime() {
        requireCompleted();
        return state.prime();
    }

    public BigInteger targetModulus() {
        requireCompleted();
        return state.targetModulus();
    }

    public List<SparsePolynomial<BigInteger>> factors() {
        requireCompleted();
        return state.factors();
    }

    public List<HenselLiftStep> steps() {
        return state.steps();
    }

    public PolynomialWorkLedger work() {
        return state.work();
    }

    public String selectionCertificateHash() {
        return state.selectionCertificateHash();
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
                "failed Hensel lift has no completed factors");
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other
            || other instanceof HenselLiftingResult result
                && state.equals(result.state);
    }

    @Override
    public int hashCode() {
        return state.hashCode();
    }

    @Override
    public String toString() {
        return "HenselLiftingResult[" + state + ']';
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
        int prime,
        BigInteger targetModulus,
        List<SparsePolynomial<BigInteger>> factors,
        List<HenselLiftStep> steps,
        PolynomialWorkLedger work,
        String selectionCertificateHash,
        String certificateHash
    ) {
        private State {
            Objects.requireNonNull(status, "status");
            factors = List.copyOf(factors);
            steps = List.copyOf(steps);
            if (detailCode == null
                    || detailCode.isBlank()
                    || prime < 0
                    || work == null
                    || selectionCertificateHash == null
                    || !selectionCertificateHash.matches(
                        "sha256:[0-9a-f]{64}")
                    || certificateHash == null
                    || !certificateHash.matches(
                        "sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "Hensel lifting result is invalid");
            }
            if (status == Status.COMPLETED) {
                if (prime < 2
                        || targetModulus == null
                        || targetModulus.signum() <= 0
                        || factors.isEmpty()) {
                    throw new IllegalArgumentException(
                        "completed Hensel lifting result is invalid");
                }
            } else if (targetModulus != null
                    || !factors.isEmpty()) {
                throw new IllegalArgumentException(
                    "failed Hensel lifting result exposes output");
            }
        }
    }
}
