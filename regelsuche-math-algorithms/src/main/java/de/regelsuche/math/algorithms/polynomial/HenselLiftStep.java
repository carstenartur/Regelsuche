package de.regelsuche.math.algorithms.polynomial;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Issuer-owned verified transition from p^e to p^(e + 1). */
public final class HenselLiftStep {
    private final State state;

    private HenselLiftStep(State state) {
        this.state = state;
    }

    static HenselLiftStep issue(
        int fromExponent,
        int toExponent,
        BigInteger fromModulus,
        BigInteger toModulus,
        String errorPolynomialHash,
        List<String> correctionPolynomialHashes,
        String liftedProductHash,
        long workUnits
    ) {
        List<String> corrections = List.copyOf(
            correctionPolynomialHashes);
        StringBuilder material = new StringBuilder(
            HenselLifting.METHOD_ID);
        AlgorithmEvidence.append(
            material,
            Integer.toString(fromExponent));
        AlgorithmEvidence.append(
            material,
            Integer.toString(toExponent));
        AlgorithmEvidence.append(material, fromModulus.toString());
        AlgorithmEvidence.append(material, toModulus.toString());
        AlgorithmEvidence.append(material, errorPolynomialHash);
        corrections.forEach(hash -> AlgorithmEvidence.append(
            material,
            hash));
        AlgorithmEvidence.append(material, liftedProductHash);
        AlgorithmEvidence.append(material, Long.toString(workUnits));
        return new HenselLiftStep(new State(
            fromExponent,
            toExponent,
            fromModulus,
            toModulus,
            errorPolynomialHash,
            corrections,
            liftedProductHash,
            workUnits,
            AlgorithmEvidence.sha256(material.toString())));
    }

    public int fromExponent() {
        return state.fromExponent();
    }

    public int toExponent() {
        return state.toExponent();
    }

    public BigInteger fromModulus() {
        return state.fromModulus();
    }

    public BigInteger toModulus() {
        return state.toModulus();
    }

    public String errorPolynomialHash() {
        return state.errorPolynomialHash();
    }

    public List<String> correctionPolynomialHashes() {
        return state.correctionPolynomialHashes();
    }

    public String liftedProductHash() {
        return state.liftedProductHash();
    }

    public long workUnits() {
        return state.workUnits();
    }

    public String certificateHash() {
        return state.certificateHash();
    }

    String canonicalMaterial() {
        StringBuilder material = new StringBuilder();
        AlgorithmEvidence.append(
            material,
            Integer.toString(fromExponent()));
        AlgorithmEvidence.append(
            material,
            Integer.toString(toExponent()));
        AlgorithmEvidence.append(
            material,
            fromModulus().toString());
        AlgorithmEvidence.append(
            material,
            toModulus().toString());
        AlgorithmEvidence.append(material, errorPolynomialHash());
        correctionPolynomialHashes().forEach(hash ->
            AlgorithmEvidence.append(material, hash));
        AlgorithmEvidence.append(material, liftedProductHash());
        AlgorithmEvidence.append(
            material,
            Long.toString(workUnits()));
        AlgorithmEvidence.append(material, certificateHash());
        return material.toString();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
            || other instanceof HenselLiftStep step
                && state.equals(step.state);
    }

    @Override
    public int hashCode() {
        return state.hashCode();
    }

    @Override
    public String toString() {
        return "HenselLiftStep[" + state + ']';
    }

    private record State(
        int fromExponent,
        int toExponent,
        BigInteger fromModulus,
        BigInteger toModulus,
        String errorPolynomialHash,
        List<String> correctionPolynomialHashes,
        String liftedProductHash,
        long workUnits,
        String certificateHash
    ) {
        private State {
            Objects.requireNonNull(fromModulus, "fromModulus");
            Objects.requireNonNull(toModulus, "toModulus");
            correctionPolynomialHashes = List.copyOf(
                correctionPolynomialHashes);
            if (fromExponent < 1
                    || toExponent != fromExponent + 1
                    || fromModulus.signum() <= 0
                    || toModulus.signum() <= 0
                    || !toModulus.mod(fromModulus).equals(
                        BigInteger.ZERO)
                    || errorPolynomialHash == null
                    || !errorPolynomialHash.matches(
                        "sha256:[0-9a-f]{64}")
                    || correctionPolynomialHashes.isEmpty()
                    || correctionPolynomialHashes.stream().anyMatch(
                        hash -> hash == null
                            || !hash.matches(
                                "sha256:[0-9a-f]{64}"))
                    || liftedProductHash == null
                    || !liftedProductHash.matches(
                        "sha256:[0-9a-f]{64}")
                    || workUnits < 1
                    || certificateHash == null
                    || !certificateHash.matches(
                        "sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "Hensel lift step is invalid");
            }
        }
    }
}
