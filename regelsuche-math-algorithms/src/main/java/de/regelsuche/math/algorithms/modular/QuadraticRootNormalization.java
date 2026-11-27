package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Exact observation after conditional joint negation of a source-bound quadratic-root pair. */
public final class QuadraticRootNormalization {
    private QuadraticRootNormalization() {}

    /**
     * One sign bit per pair of canonical coordinate classes: zero (0), nonzero even (1), odd (2).
     * Bit index is 3 * realClass + imaginaryClass. The zero pair's sign bit is arbitrary.
     */
    public record ParitySelector(int mask) {
        public ParitySelector {
            if (mask < 0 || mask >= 1 << 9) throw new IllegalArgumentException("INVALID_PARITY_SELECTOR");
        }

        /** Parity of the real coordinate, falling back to the imaginary coordinate on the imaginary axis. */
        public static ParitySelector realFirst() { return new ParitySelector(0b111_000_100); }

        /** Parity of the imaginary coordinate, falling back to the real coordinate on the real axis. */
        public static ParitySelector imaginaryFirst() { return new ParitySelector(0b101_100_100); }

        private int bit(int realClass, int imaginaryClass) {
            return (mask >>> (3 * realClass + imaginaryClass)) & 1;
        }
    }

    /**
     * Source operation that retains the pair iff its selector equals an unchanged target bit (0 or 1),
     * and otherwise negates both coordinates modulo the stated modulus. A compiler must independently
     * bind these semantics and the provenance of the target at the indicated observation site.
     */
    public record Contract(String sourceIdentity, BigInteger modulus, ParitySelector selector) {
        public Contract {
            Objects.requireNonNull(sourceIdentity);
            if (sourceIdentity.length() > 1024 || sourceIdentity.isBlank()) {
                throw new IllegalArgumentException("NORMALIZER_SOURCE_ID");
            }
            SharedPowerPlan.bounded(modulus);
            Objects.requireNonNull(selector);
        }
    }

    /** Untrusted binding of independently replayable root evidence and the downstream observation. */
    public record Certificate(QuadraticRootSearch.Certificate root, Contract normalizer) {
        public Certificate {
            Objects.requireNonNull(root);
            Objects.requireNonNull(normalizer);
        }
    }

    /** Verify exact equality after the stated normalization, for either common target bit. */
    public static QuadraticRootVerifier.Result verifyNormalized(QuadraticRootContract expectedRoot, Contract expectedNormalizer,
            Certificate supplied, IterationLimits limits, BooleanSupplier cancellation) {
        var work = new CertificateWork(limits, cancellation);
        try {
            work.charge(1);
            if (expectedRoot == null || expectedNormalizer == null || supplied == null) {
                throw new IllegalArgumentException("MISSING_NORMALIZATION_CONTRACT_OR_CERTIFICATE");
            }
            work.charge(1L + expectedNormalizer.sourceIdentity().length() + 2L * SharedPowerVerifier.words(expectedNormalizer.modulus()));
            if (!expectedNormalizer.equals(supplied.normalizer()) || !expectedNormalizer.modulus().equals(expectedRoot.modulus())) {
                throw new IllegalArgumentException("NORMALIZER_SOURCE_MISMATCH");
            }
            QuadraticRootVerifier.checkField(expectedRoot, work, cancellation);
            QuadraticRootVerifier.verifyProgram(expectedRoot, supplied.root(), work, cancellation);
            checkSelector(expectedNormalizer.selector(), work);
            work.charge(0);
            return new QuadraticRootVerifier.Result(true, "VERIFIED", work.used());
        } catch (CertificateWork.Stop | IllegalArgumentException ex) {
            return new QuadraticRootVerifier.Result(false, ex.getMessage(), work.used());
        }
    }

    private static void checkSelector(ParitySelector selector, CertificateWork work) {
        // At the zero pair both conditional exits coincide, regardless of the selector or target.
        for (int cell = 1; cell < 9; cell++) {
            work.charge(1);
            int real = cell / 3;
            int imaginary = cell % 3;
            if (selector.bit(real, imaginary) == selector.bit(negatedClass(real), negatedClass(imaginary))) {
                throw new IllegalArgumentException("SELECTOR_DOES_NOT_REVERSE_SIGN");
            }
        }
    }

    private static int negatedClass(int coordinateClass) {
        // For odd p, the canonical negative of 0 < x < p is p - x, with the opposite parity.
        return coordinateClass == 0 ? 0 : 3 - coordinateClass;
    }
}
