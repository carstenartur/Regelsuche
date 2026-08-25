package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.PrimeField;
import de.regelsuche.polynomial.SparsePolynomial;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * Issuer-owned evidence for one bounded suitable-prime selection campaign.
 *
 * <p>Rejected primes remain visible with their exact terminal reason and work.
 * A completed result additionally retains the selected modular source and its
 * independently verified finite-field factorization.</p>
 */
public final class SuitablePrimeSelectionResult {
    private final State state;

    private SuitablePrimeSelectionResult(State state) {
        this.state = state;
    }

    static SuitablePrimeSelectionResult completed(
        List<PrimeAttempt> attempts,
        int selectedPrime,
        SparsePolynomial<BigInteger> modularSource,
        FiniteFieldFactorizationResult modularFactorization,
        PolynomialWorkLedger work,
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionPolicy policy
    ) {
        return create(
            Status.COMPLETED,
            "SUITABLE_PRIME_AND_MODULAR_FACTORIZATION_VERIFIED",
            attempts,
            selectedPrime,
            modularSource,
            modularFactorization,
            work,
            request,
            policy);
    }

    static SuitablePrimeSelectionResult failure(
        Status status,
        String detailCode,
        List<PrimeAttempt> attempts,
        PolynomialWorkLedger work,
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionPolicy policy
    ) {
        if (status == Status.COMPLETED) {
            throw new IllegalArgumentException(
                "completed suitable-prime result requires a selected prime");
        }
        return create(
            status,
            detailCode,
            attempts,
            0,
            null,
            null,
            work,
            request,
            policy);
    }

    private static SuitablePrimeSelectionResult create(
        Status status,
        String detailCode,
        List<PrimeAttempt> attempts,
        int selectedPrime,
        SparsePolynomial<BigInteger> modularSource,
        FiniteFieldFactorizationResult modularFactorization,
        PolynomialWorkLedger work,
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionPolicy policy
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(work, "work");
        List<PrimeAttempt> retainedAttempts = List.copyOf(attempts);
        validateAttemptSequence(retainedAttempts, policy);
        String sourceDomainId = request.source()
            .ring()
            .coefficientDomain()
            .id();

        StringBuilder material = new StringBuilder(
            SuitablePrimeSelection.METHOD_ID);
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
        retainedAttempts.forEach(attempt ->
            AlgorithmEvidence.append(
                material,
                attempt.canonicalMaterial()));
        AlgorithmEvidence.append(
            material,
            Integer.toString(selectedPrime));
        AlgorithmEvidence.append(
            material,
            modularSource == null
                ? ""
                : modularSource.canonicalMaterial());
        AlgorithmEvidence.append(
            material,
            modularFactorization == null
                ? ""
                : modularFactorization.certificateHash());

        return new SuitablePrimeSelectionResult(new State(
            status,
            detailCode,
            sourceDomainId,
            retainedAttempts,
            selectedPrime,
            modularSource,
            modularFactorization,
            work,
            AlgorithmEvidence.sha256(material.toString())));
    }

    private static void validateAttemptSequence(
        List<PrimeAttempt> attempts,
        SuitablePrimeSelectionPolicy policy
    ) {
        if (attempts.size() > policy.candidatePrimes().size()) {
            throw new IllegalArgumentException(
                "suitable-prime attempts exceed the policy sequence");
        }
        for (int index = 0; index < attempts.size(); index++) {
            PrimeAttempt attempt = attempts.get(index);
            if (attempt.prime()
                    != policy.candidatePrimes().get(index)) {
                throw new IllegalArgumentException(
                    "suitable-prime attempts do not follow the policy sequence");
            }
            if (index + 1 < attempts.size()
                    && attempt.disposition()
                        != PrimeAttempt.Disposition.REJECTED) {
                throw new IllegalArgumentException(
                    "only the final suitable-prime attempt may be terminal");
            }
        }
    }

    static PrimeAttempt issueAttempt(
        int prime,
        PrimeAttempt.Disposition disposition,
        String detailCode,
        SparsePolynomial<BigInteger> modularSource,
        String modularFactorizationCertificateHash,
        long workUnits
    ) {
        Objects.requireNonNull(modularSource, "modularSource");
        String modularSourceHash = AlgorithmEvidence.sha256(
            modularSource.canonicalMaterial());
        String factorizationHash =
            modularFactorizationCertificateHash == null
                ? ""
                : modularFactorizationCertificateHash;
        StringBuilder material = new StringBuilder(
            SuitablePrimeSelection.METHOD_ID);
        AlgorithmEvidence.append(material, Integer.toString(prime));
        AlgorithmEvidence.append(material, disposition.name());
        AlgorithmEvidence.append(material, detailCode);
        AlgorithmEvidence.append(material, modularSourceHash);
        AlgorithmEvidence.append(material, factorizationHash);
        AlgorithmEvidence.append(material, Long.toString(workUnits));
        return new PrimeAttempt(
            prime,
            disposition,
            detailCode,
            modularSourceHash,
            factorizationHash,
            workUnits,
            AlgorithmEvidence.sha256(material.toString()));
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

    public List<PrimeAttempt> attempts() {
        return state.attempts();
    }

    public int selectedPrime() {
        requireCompleted();
        return state.selectedPrime();
    }

    public SparsePolynomial<BigInteger> modularSource() {
        requireCompleted();
        return state.modularSource();
    }

    public FiniteFieldFactorizationResult modularFactorization() {
        requireCompleted();
        return state.modularFactorization();
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
                "failed suitable-prime selection has no selected output");
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other
            || other instanceof SuitablePrimeSelectionResult result
                && state.equals(result.state);
    }

    @Override
    public int hashCode() {
        return state.hashCode();
    }

    @Override
    public String toString() {
        return "SuitablePrimeSelectionResult[" + state + ']';
    }

    public enum Status {
        COMPLETED,
        UNSUPPORTED_DOMAIN,
        UNSUPPORTED_SHAPE,
        BUDGET_INCONCLUSIVE,
        TECHNICAL_FAILURE
    }

    /** One package-issued prime attempt in canonical policy order. */
    public static final class PrimeAttempt {
        private final AttemptState state;

        private PrimeAttempt(
            int prime,
            Disposition disposition,
            String detailCode,
            String modularSourceHash,
            String modularFactorizationCertificateHash,
            long workUnits,
            String certificateHash
        ) {
            state = new AttemptState(
                prime,
                disposition,
                detailCode,
                modularSourceHash,
                modularFactorizationCertificateHash,
                workUnits,
                certificateHash);
        }

        public int prime() {
            return state.prime();
        }

        public Disposition disposition() {
            return state.disposition();
        }

        public String detailCode() {
            return state.detailCode();
        }

        public String modularSourceHash() {
            return state.modularSourceHash();
        }

        public String modularFactorizationCertificateHash() {
            return state.modularFactorizationCertificateHash();
        }

        public long workUnits() {
            return state.workUnits();
        }

        public String certificateHash() {
            return state.certificateHash();
        }

        private String canonicalMaterial() {
            StringBuilder material = new StringBuilder();
            AlgorithmEvidence.append(
                material,
                Integer.toString(prime()));
            AlgorithmEvidence.append(
                material,
                disposition().name());
            AlgorithmEvidence.append(material, detailCode());
            AlgorithmEvidence.append(material, modularSourceHash());
            AlgorithmEvidence.append(
                material,
                modularFactorizationCertificateHash());
            AlgorithmEvidence.append(
                material,
                Long.toString(workUnits()));
            AlgorithmEvidence.append(material, certificateHash());
            return material.toString();
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                || other instanceof PrimeAttempt attempt
                    && state.equals(attempt.state);
        }

        @Override
        public int hashCode() {
            return state.hashCode();
        }

        @Override
        public String toString() {
            return "PrimeAttempt[" + state + ']';
        }

        public enum Disposition {
            REJECTED,
            SELECTED,
            TERMINAL_INCONCLUSIVE,
            TERMINAL_FAILURE
        }

        private record AttemptState(
            int prime,
            Disposition disposition,
            String detailCode,
            String modularSourceHash,
            String modularFactorizationCertificateHash,
            long workUnits,
            String certificateHash
        ) {
            private AttemptState {
                Objects.requireNonNull(disposition, "disposition");
                if (prime < 2
                        || detailCode == null
                        || detailCode.isBlank()
                        || modularSourceHash == null
                        || !modularSourceHash.matches(
                            "sha256:[0-9a-f]{64}")
                        || modularFactorizationCertificateHash == null
                        || !modularFactorizationCertificateHash.isEmpty()
                            && !modularFactorizationCertificateHash.matches(
                                "sha256:[0-9a-f]{64}")
                        || workUnits < 0
                        || certificateHash == null
                        || !certificateHash.matches(
                            "sha256:[0-9a-f]{64}")) {
                    throw new IllegalArgumentException(
                        "suitable-prime attempt is invalid");
                }
                if (disposition != Disposition.REJECTED
                        && modularFactorizationCertificateHash.isEmpty()) {
                    throw new IllegalArgumentException(
                        "terminal prime attempt requires factorization evidence");
                }
            }
        }
    }

    private record State(
        Status status,
        String detailCode,
        String sourceDomainId,
        List<PrimeAttempt> attempts,
        int selectedPrime,
        SparsePolynomial<BigInteger> modularSource,
        FiniteFieldFactorizationResult modularFactorization,
        PolynomialWorkLedger work,
        String certificateHash
    ) {
        private State {
            Objects.requireNonNull(status, "status");
            if (detailCode == null
                    || detailCode.isBlank()
                    || sourceDomainId == null
                    || sourceDomainId.isBlank()
                    || attempts == null
                    || work == null
                    || certificateHash == null
                    || !certificateHash.matches(
                        "sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "suitable-prime selection result is invalid");
            }
            attempts = List.copyOf(attempts);
            long selectedAttempts = attempts.stream()
                .filter(attempt -> attempt.disposition()
                    == PrimeAttempt.Disposition.SELECTED)
                .count();
            if (status == Status.COMPLETED) {
                validateCompleted(
                    sourceDomainId,
                    attempts,
                    selectedAttempts,
                    selectedPrime,
                    modularSource,
                    modularFactorization);
            } else if (selectedPrime != 0
                    || modularSource != null
                    || modularFactorization != null
                    || selectedAttempts != 0) {
                throw new IllegalArgumentException(
                    "failed suitable-prime result cannot expose selected output");
            }
        }

        private static void validateCompleted(
            String sourceDomainId,
            List<PrimeAttempt> attempts,
            long selectedAttempts,
            int selectedPrime,
            SparsePolynomial<BigInteger> modularSource,
            FiniteFieldFactorizationResult modularFactorization
        ) {
            Objects.requireNonNull(modularSource, "modularSource");
            Objects.requireNonNull(
                modularFactorization,
                "modularFactorization");
            PrimeAttempt selectedAttempt = attempts.isEmpty()
                ? null
                : attempts.getLast();
            String modularSourceHash = AlgorithmEvidence.sha256(
                modularSource.canonicalMaterial());
            if (!BigIntegerDomain.DOMAIN_ID.equals(sourceDomainId)
                    || selectedPrime < 2
                    || selectedAttempt == null
                    || selectedAttempts != 1
                    || selectedAttempt.disposition()
                        != PrimeAttempt.Disposition.SELECTED
                    || selectedAttempt.prime() != selectedPrime
                    || !selectedAttempt.modularSourceHash()
                        .equals(modularSourceHash)
                    || !(modularSource.ring().coefficientDomain()
                        instanceof PrimeField field)
                    || field.prime() != selectedPrime
                    || !modularFactorization.completed()
                    || modularFactorization.prime() != selectedPrime
                    || !selectedAttempt
                        .modularFactorizationCertificateHash()
                        .equals(modularFactorization.certificateHash())) {
                throw new IllegalArgumentException(
                    "completed suitable-prime selection is invalid");
            }
        }
    }
}
