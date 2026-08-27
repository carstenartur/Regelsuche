package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.SparsePolynomial;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Issuer-owned result of deterministic integer-factor recombination. */
public final class ZassenhausRecombinationResult {
    private final State state;

    private ZassenhausRecombinationResult(State state) {
        this.state = state;
    }

    static ZassenhausRecombinationResult completed(
        BigInteger coefficientBound,
        BigInteger targetModulus,
        List<SparsePolynomial<BigInteger>> factors,
        List<List<Integer>> modularPartitions,
        long candidatesConsidered,
        String candidateAuditHash,
        PolynomialWorkLedger work,
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingResult lifting,
        ZassenhausRecombinationPolicy policy
    ) {
        List<SparsePolynomial<BigInteger>> retainedFactors =
            List.copyOf(factors);
        List<List<Integer>> retainedPartitions =
            canonicalPartitions(modularPartitions);
        ZassenhausEvidence.validateCompleted(
            coefficientBound,
            targetModulus,
            retainedFactors,
            retainedPartitions,
            candidatesConsidered,
            work,
            request,
            selection,
            lifting,
            policy);
        return create(
            Status.COMPLETED,
            "ZASSENHAUS_INTEGER_FACTORIZATION_VERIFIED",
            coefficientBound,
            targetModulus,
            retainedFactors,
            retainedPartitions,
            candidatesConsidered,
            candidateAuditHash,
            work,
            request,
            selection,
            lifting,
            policy);
    }

    static ZassenhausRecombinationResult failure(
        Status status,
        String detailCode,
        BigInteger coefficientBound,
        long candidatesConsidered,
        String candidateAuditHash,
        PolynomialWorkLedger work,
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingResult lifting,
        ZassenhausRecombinationPolicy policy
    ) {
        if (status == Status.COMPLETED) {
            throw new IllegalArgumentException(
                "completed recombination requires factors");
        }
        return create(
            status,
            detailCode,
            coefficientBound,
            null,
            List.of(),
            List.of(),
            candidatesConsidered,
            candidateAuditHash,
            work,
            request,
            selection,
            lifting,
            policy);
    }

    private static ZassenhausRecombinationResult create(
        Status status,
        String detailCode,
        BigInteger coefficientBound,
        BigInteger targetModulus,
        List<SparsePolynomial<BigInteger>> factors,
        List<List<Integer>> modularPartitions,
        long candidatesConsidered,
        String candidateAuditHash,
        PolynomialWorkLedger work,
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingResult lifting,
        ZassenhausRecombinationPolicy policy
    ) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(lifting, "lifting");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(work, "work");
        if (detailCode == null
                || detailCode.isBlank()
                || candidatesConsidered < 0
                || candidateAuditHash == null
                || !candidateAuditHash.matches(
                    "sha256:[0-9a-f]{64}")
                || status != Status.COMPLETED
                    && (targetModulus != null
                        || !factors.isEmpty()
                        || !modularPartitions.isEmpty())) {
            throw new IllegalArgumentException(
                "Zassenhaus result metadata is invalid");
        }

        StringBuilder material = new StringBuilder(
            ZassenhausRecombination.METHOD_ID);
        AlgorithmEvidence.append(
            material,
            request.canonicalMaterial());
        AlgorithmEvidence.append(
            material,
            selection.certificateHash());
        AlgorithmEvidence.append(
            material,
            lifting.certificateHash());
        AlgorithmEvidence.append(
            material,
            policy.canonicalMaterial());
        AlgorithmEvidence.append(material, status.name());
        AlgorithmEvidence.append(material, detailCode);
        AlgorithmEvidence.append(
            material,
            coefficientBound == null
                ? ""
                : coefficientBound.toString());
        AlgorithmEvidence.append(
            material,
            targetModulus == null
                ? ""
                : targetModulus.toString());
        AlgorithmEvidence.append(
            material,
            Long.toString(candidatesConsidered));
        AlgorithmEvidence.append(
            material,
            candidateAuditHash);
        AlgorithmEvidence.append(
            material,
            work.canonicalMaterial());
        for (int index = 0; index < factors.size(); index++) {
            AlgorithmEvidence.append(
                material,
                factors.get(index).canonicalMaterial());
            AlgorithmEvidence.append(
                material,
                modularPartitions.get(index).toString());
        }

        return new ZassenhausRecombinationResult(new State(
            status,
            detailCode,
            coefficientBound,
            targetModulus,
            List.copyOf(factors),
            List.copyOf(modularPartitions),
            candidatesConsidered,
            candidateAuditHash,
            work,
            selection.certificateHash(),
            lifting.certificateHash(),
            AlgorithmEvidence.sha256(material.toString())));
    }

    private static List<List<Integer>> canonicalPartitions(
        List<List<Integer>> partitions
    ) {
        ArrayList<List<Integer>> result = new ArrayList<>();
        for (List<Integer> partition :
                Objects.requireNonNull(partitions, "partitions")) {
            List<Integer> ordered = partition.stream()
                .sorted()
                .distinct()
                .toList();
            if (ordered.isEmpty()
                    || ordered.size() != partition.size()) {
                throw new IllegalArgumentException(
                    "Zassenhaus partition must be nonempty and unique");
            }
            result.add(ordered);
        }
        return List.copyOf(result);
    }

    public Status status() {
        return state.status();
    }

    public String detailCode() {
        return state.detailCode();
    }

    public BigInteger coefficientBound() {
        requireCompleted();
        return state.coefficientBound();
    }

    public BigInteger targetModulus() {
        requireCompleted();
        return state.targetModulus();
    }

    public List<SparsePolynomial<BigInteger>> factors() {
        return state.factors();
    }

    public List<List<Integer>> modularPartitions() {
        return state.modularPartitions();
    }

    public long candidatesConsidered() {
        return state.candidatesConsidered();
    }

    public String candidateAuditHash() {
        return state.candidateAuditHash();
    }

    public PolynomialWorkLedger work() {
        return state.work();
    }

    public String selectionCertificateHash() {
        return state.selectionCertificateHash();
    }

    public String liftingCertificateHash() {
        return state.liftingCertificateHash();
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
                "failed recombination has no completed output");
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other
            || other instanceof ZassenhausRecombinationResult result
                && state.equals(result.state);
    }

    @Override
    public int hashCode() {
        return state.hashCode();
    }

    @Override
    public String toString() {
        return "ZassenhausRecombinationResult[" + state + ']';
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
        BigInteger coefficientBound,
        BigInteger targetModulus,
        List<SparsePolynomial<BigInteger>> factors,
        List<List<Integer>> modularPartitions,
        long candidatesConsidered,
        String candidateAuditHash,
        PolynomialWorkLedger work,
        String selectionCertificateHash,
        String liftingCertificateHash,
        String certificateHash
    ) {
        private State {
            Objects.requireNonNull(status, "status");
            factors = List.copyOf(factors);
            modularPartitions = List.copyOf(modularPartitions);
            if (detailCode == null
                    || detailCode.isBlank()
                    || candidatesConsidered < 0
                    || candidateAuditHash == null
                    || !candidateAuditHash.matches(
                        "sha256:[0-9a-f]{64}")
                    || work == null
                    || selectionCertificateHash == null
                    || !selectionCertificateHash.matches(
                        "sha256:[0-9a-f]{64}")
                    || liftingCertificateHash == null
                    || !liftingCertificateHash.matches(
                        "sha256:[0-9a-f]{64}")
                    || certificateHash == null
                    || !certificateHash.matches(
                        "sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "Zassenhaus result is invalid");
            }
        }
    }
}
