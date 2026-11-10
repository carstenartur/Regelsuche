package de.regelsuche.egraph.proof;

/** Separate producer and replay resource envelopes. */
public record ProofLimits(
        int maxTerms,
        int maxProofSteps,
        int maxReferences,
        int maxCharacters,
        int maxRationalBits,
        int maxDepth,
        int maxExpandedNodes,
        long maxWork) {
    public ProofLimits {
        if (maxTerms < 0
                || maxProofSteps < 0
                || maxReferences < 0
                || maxCharacters < 0
                || maxRationalBits < 0
                || maxDepth < 0
                || maxExpandedNodes < 0
                || maxWork < 0) throw new IllegalArgumentException("negative proof limit");
    }

    public static ProofLimits defaults() {
        return new ProofLimits(50_000, 100_000, 500_000, 1_000_000, 4096, 256, 100_000, 5_000_000L);
    }
}
