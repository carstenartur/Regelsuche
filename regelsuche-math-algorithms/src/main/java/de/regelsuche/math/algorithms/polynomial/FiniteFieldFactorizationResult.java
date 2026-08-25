package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.PrimeField;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Issuer-owned complete factorization evidence over one declared prime field. */
public final class FiniteFieldFactorizationResult {
    private final State state;

    private FiniteFieldFactorizationResult(State state) {
        this.state = state;
    }

    static FiniteFieldFactorizationResult completed(
        BigInteger unit,
        List<PolynomialFactor<BigInteger>> factors,
        int berlekampNullity,
        String kernelCertificateHash,
        List<String> irreducibilityCertificateHashes,
        PolynomialWorkLedger work,
        FactorizationRequest<BigInteger> request,
        FiniteFieldFactorizationPolicy policy,
        PrimeField field
    ) {
        return create(
            Status.COMPLETED,
            "PRIME_FIELD_FACTORIZATION_VERIFIED_COMPLETE",
            unit,
            factors,
            berlekampNullity,
            kernelCertificateHash,
            irreducibilityCertificateHashes,
            work,
            request,
            policy,
            field);
    }

    static FiniteFieldFactorizationResult failure(
        Status status,
        String detailCode,
        PolynomialWorkLedger work,
        FactorizationRequest<BigInteger> request,
        FiniteFieldFactorizationPolicy policy,
        PrimeField field
    ) {
        if (status == Status.COMPLETED) {
            throw new IllegalArgumentException(
                "completed finite-field result requires factors");
        }
        return create(
            status,
            detailCode,
            null,
            List.of(),
            0,
            null,
            List.of(),
            work,
            request,
            policy,
            field);
    }

    private static FiniteFieldFactorizationResult create(
        Status status,
        String detailCode,
        BigInteger unit,
        List<PolynomialFactor<BigInteger>> factors,
        int berlekampNullity,
        String kernelCertificateHash,
        List<String> irreducibilityCertificateHashes,
        PolynomialWorkLedger work,
        FactorizationRequest<BigInteger> request,
        FiniteFieldFactorizationPolicy policy,
        PrimeField field
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(work, "work");
        String sourceDomainId = request.source()
            .ring()
            .coefficientDomain()
            .id();
        int prime = status == Status.COMPLETED
            ? Objects.requireNonNull(field, "field").prime()
            : 0;
        List<PolynomialFactor<BigInteger>> ordered = factors.stream()
            .sorted(Comparator.comparing(factor ->
                factor.polynomial().canonicalMaterial()))
            .toList();
        List<String> irreducibilityHashes =
            List.copyOf(irreducibilityCertificateHashes);

        StringBuilder material = new StringBuilder(
            FiniteFieldFactorization.METHOD_ID);
        AlgorithmEvidence.append(material, sourceDomainId);
        AlgorithmEvidence.append(
            material,
            request.canonicalMaterial());
        AlgorithmEvidence.append(
            material,
            policy.canonicalMaterial());
        AlgorithmEvidence.append(material, status.name());
        AlgorithmEvidence.append(material, detailCode);
        AlgorithmEvidence.append(material, Integer.toString(prime));
        AlgorithmEvidence.append(
            material,
            work.canonicalMaterial());
        AlgorithmEvidence.append(
            material,
            unit == null ? "" : unit.toString());
        AlgorithmEvidence.append(
            material,
            Integer.toString(berlekampNullity));
        AlgorithmEvidence.append(
            material,
            kernelCertificateHash == null
                ? ""
                : kernelCertificateHash);
        ordered.forEach(factor -> {
            AlgorithmEvidence.append(
                material,
                Integer.toString(factor.multiplicity()));
            AlgorithmEvidence.append(
                material,
                factor.polynomial().canonicalMaterial());
        });
        irreducibilityHashes.forEach(hash ->
            AlgorithmEvidence.append(material, hash));

        return new FiniteFieldFactorizationResult(new State(
            status,
            detailCode,
            sourceDomainId,
            prime,
            unit,
            ordered,
            berlekampNullity,
            kernelCertificateHash,
            irreducibilityHashes,
            work,
            AlgorithmEvidence.sha256(material.toString())));
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

    public int prime() {
        requireCompleted();
        return state.prime();
    }

    public BigInteger unit() {
        requireCompleted();
        return state.unit();
    }

    public List<PolynomialFactor<BigInteger>> factors() {
        return state.factors();
    }

    public int berlekampNullity() {
        requireCompleted();
        return state.berlekampNullity();
    }

    public String kernelCertificateHash() {
        requireCompleted();
        return state.kernelCertificateHash();
    }

    public List<String> irreducibilityCertificateHashes() {
        return state.irreducibilityCertificateHashes();
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
                "failed finite-field factorization has no exact output");
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other
            || other instanceof FiniteFieldFactorizationResult result
                && state.equals(result.state);
    }

    @Override
    public int hashCode() {
        return state.hashCode();
    }

    @Override
    public String toString() {
        return "FiniteFieldFactorizationResult[" + state + ']';
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
        int prime,
        BigInteger unit,
        List<PolynomialFactor<BigInteger>> factors,
        int berlekampNullity,
        String kernelCertificateHash,
        List<String> irreducibilityCertificateHashes,
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
                    "finite-field factorization result is invalid");
            }
            factors = List.copyOf(factors);
            irreducibilityCertificateHashes =
                List.copyOf(irreducibilityCertificateHashes);
            if (status == Status.COMPLETED) {
                validateCompleted(
                    prime,
                    unit,
                    factors,
                    berlekampNullity,
                    kernelCertificateHash,
                    irreducibilityCertificateHashes);
            } else if (prime != 0
                    || unit != null
                    || !factors.isEmpty()
                    || berlekampNullity != 0
                    || kernelCertificateHash != null
                    || !irreducibilityCertificateHashes.isEmpty()) {
                throw new IllegalArgumentException(
                    "failed finite-field result cannot expose factors");
            }
        }

        private static void validateCompleted(
            int prime,
            BigInteger unit,
            List<PolynomialFactor<BigInteger>> factors,
            int berlekampNullity,
            String kernelCertificateHash,
            List<String> irreducibilityCertificateHashes
        ) {
            Objects.requireNonNull(unit, "unit");
            if (prime < 2
                    || unit.signum() == 0
                    || factors.isEmpty()
                    || berlekampNullity != factors.size()
                    || kernelCertificateHash == null
                    || !kernelCertificateHash.matches(
                        "sha256:[0-9a-f]{64}")
                    || irreducibilityCertificateHashes.size()
                        != factors.size()
                    || irreducibilityCertificateHashes.stream()
                        .anyMatch(hash -> hash == null
                            || !hash.matches(
                                "sha256:[0-9a-f]{64}"))) {
                throw new IllegalArgumentException(
                    "completed finite-field result is invalid");
            }
            String expectedDomain = PrimeField.of(prime).id();
            if (factors.stream().anyMatch(factor ->
                    factor.multiplicity() != 1
                        || !expectedDomain.equals(
                            factor.polynomial().ring()
                                .coefficientDomain().id())
                        || !BigInteger.ONE.equals(
                            factor.polynomial()
                                .leadingCoefficient()))) {
                throw new IllegalArgumentException(
                    "finite-field factors are not canonical");
            }
        }
    }
}
