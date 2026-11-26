package de.regelsuche.math.algorithms.modular;

import java.math.BigInteger;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Lookup of untrusted evidence. The verifier must still replay it against the observed modulus. */
@FunctionalInterface
public interface PrimeEvidenceProvider {
    Optional<PrimeEvidence.Certificate> forModulus(BigInteger modulus);

    static PrimeEvidenceProvider catalog() {
        return PrimeEvidenceCatalog::forModulus;
    }

    static PrimeEvidenceProvider supplied(PrimeEvidence.Certificate certificate) {
        return supplied(List.of(certificate));
    }

    /** Index at most 64 supplied proofs by their claimed moduli. Reject duplicate keys; use the catalog for misses. */
    static PrimeEvidenceProvider supplied(Collection<? extends PrimeEvidence.Certificate> certificates) {
        var entries = new HashMap<BigInteger, PrimeEvidence.Certificate>();
        for (var certificate : certificates) {
            if (entries.size() == 64) throw new IllegalArgumentException("PRIME_PROVIDER_LIMIT");
            var modulus = Objects.requireNonNull(certificate).modulus();
            if (entries.putIfAbsent(modulus, certificate) != null) throw new IllegalArgumentException("DUPLICATE_MODULUS");
        }
        var index = Map.copyOf(entries);
        return modulus -> {
            if (!PrimeEvidence.bounded(modulus)) return Optional.empty();
            var supplied = index.get(modulus);
            return supplied == null ? PrimeEvidenceCatalog.forModulus(modulus) : Optional.of(supplied);
        };
    }
}
