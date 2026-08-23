package de.regelsuche.evolution;

import java.util.Objects;

/**
 * Public string-pattern boundary for the exact polynomial verifier.
 *
 * <p>The evolution compiler intentionally keeps its parser package-private.
 * Mining and campaign layers nevertheless need to verify the same textual
 * patterns that are emitted by candidate generalization. This adapter keeps
 * parsing and exact verification inside the learning module instead of
 * duplicating either implementation in application code.</p>
 */
public final class ExactPolynomialPatternVerificationService {
    private final ExactPolynomialPatternIdentityVerifier verifier;

    public ExactPolynomialPatternVerificationService() {
        this(new ExactPolynomialPatternIdentityVerifier());
    }

    public ExactPolynomialPatternVerificationService(
        ExactPolynomialPatternIdentityVerifier verifier
    ) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    public ExactPolynomialPatternIdentityVerifier.Verification verify(
        String sourcePattern,
        String targetPattern
    ) {
        requireText(sourcePattern, "sourcePattern");
        requireText(targetPattern, "targetPattern");
        return verifier.verify(
            EvolutionGenomeCompiler.parsePattern(sourcePattern),
            EvolutionGenomeCompiler.parsePattern(targetPattern));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
