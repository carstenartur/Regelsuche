package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.PrimeField;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ordered prime candidates and the retained modular factorization policy.
 *
 * <p>The candidate sequence is part of the evidence contract. It is strictly
 * increasing, contains only supported prime moduli and cannot exceed the
 * finite-field enumeration authority.</p>
 */
public record SuitablePrimeSelectionPolicy(
    Algorithm algorithm,
    List<Integer> candidatePrimes,
    FiniteFieldFactorizationPolicy factorizationPolicy
) {
    public static final int MAX_CANDIDATE_PRIMES = 4_096;
    public static final int MAX_CANDIDATE_VALUE =
        FiniteFieldFactorizationPolicy.MAX_FIELD_ELEMENTS;

    public SuitablePrimeSelectionPolicy {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(candidatePrimes, "candidatePrimes");
        Objects.requireNonNull(
            factorizationPolicy,
            "factorizationPolicy");
        candidatePrimes = List.copyOf(candidatePrimes);
        if (candidatePrimes.isEmpty()
                || candidatePrimes.size() > MAX_CANDIDATE_PRIMES) {
            throw new IllegalArgumentException(
                "suitable-prime candidate sequence is invalid");
        }

        int previous = 1;
        for (Integer candidateValue : candidatePrimes) {
            if (candidateValue == null) {
                throw new IllegalArgumentException(
                    "suitable-prime candidate must not be null");
            }
            int candidate = candidateValue;
            if (candidate <= previous
                    || candidate > MAX_CANDIDATE_VALUE
                    || candidate
                        > factorizationPolicy
                            .maxEnumeratedFieldElements()) {
                throw new IllegalArgumentException(
                    "suitable-prime candidates must be increasing and bounded");
            }
            PrimeField.of(candidate);
            previous = candidate;
        }
    }

    public static SuitablePrimeSelectionPolicy deterministicAscending(
        int maximumPrime,
        int maximumPrimes,
        FiniteFieldFactorizationPolicy factorizationPolicy
    ) {
        Objects.requireNonNull(
            factorizationPolicy,
            "factorizationPolicy");
        if (maximumPrime < 2
                || maximumPrime > MAX_CANDIDATE_VALUE
                || maximumPrime
                    > factorizationPolicy
                        .maxEnumeratedFieldElements()
                || maximumPrimes < 1
                || maximumPrimes > MAX_CANDIDATE_PRIMES) {
            throw new IllegalArgumentException(
                "deterministic suitable-prime bounds are invalid");
        }

        ArrayList<Integer> primes = new ArrayList<>();
        for (int candidate = 2;
                candidate <= maximumPrime
                    && primes.size() < maximumPrimes;
                candidate++) {
            if (isPrime(candidate)) {
                primes.add(candidate);
            }
        }
        if (primes.isEmpty()) {
            throw new IllegalArgumentException(
                "deterministic suitable-prime policy has no candidates");
        }
        return new SuitablePrimeSelectionPolicy(
            Algorithm.DETERMINISTIC_ASCENDING_PRIMES_V1,
            primes,
            factorizationPolicy);
    }

    public String canonicalMaterial() {
        StringBuilder result = new StringBuilder();
        AlgorithmEvidence.append(result, algorithm.id());
        AlgorithmEvidence.append(
            result,
            FiniteFieldFactorization.METHOD_ID);
        AlgorithmEvidence.append(
            result,
            factorizationPolicy.canonicalMaterial());
        AlgorithmEvidence.append(
            result,
            Integer.toString(candidatePrimes.size()));
        candidatePrimes.forEach(candidate ->
            AlgorithmEvidence.append(
                result,
                Integer.toString(candidate)));
        return result.toString();
    }

    private static boolean isPrime(int candidate) {
        if (candidate == 2) {
            return true;
        }
        if (candidate < 2 || (candidate & 1) == 0) {
            return false;
        }
        for (int divisor = 3;
                (long) divisor * divisor <= candidate;
                divisor += 2) {
            if (candidate % divisor == 0) {
                return false;
            }
        }
        return true;
    }

    public enum Algorithm {
        DETERMINISTIC_ASCENDING_PRIMES_V1(
            "regelsuche.suitable-prime-selection.ascending/v1");

        private final String id;

        Algorithm(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }
}
