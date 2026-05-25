package de.regelsuche.mining;

import de.regelsuche.validation.CandidateProofStatus;

/**
 * Configures how the discovery loop collects search paths for rule mining.
 *
 * <p>Allows the miner to consider equivalent but not strictly score-improving
 * paths so that textbook normal forms can emerge from atomic rewrite steps
 * without injecting a special scorer or a special-case rule.</p>
 */
public record DiscoverySettings(
    boolean includeNonImprovingEquivalentPaths,
    int maxPathLengthForCandidateMining,
    int minExamplesPerCandidate,
    CandidateProofStatus minReusableStatus
) {
    public DiscoverySettings {
        if (maxPathLengthForCandidateMining < 1) {
            throw new IllegalArgumentException("maxPathLengthForCandidateMining must be positive");
        }
        if (minExamplesPerCandidate < 1) {
            throw new IllegalArgumentException("minExamplesPerCandidate must be positive");
        }
        if (minReusableStatus == null) {
            minReusableStatus = CandidateProofStatus.VALIDATED_BY_EXAMPLES;
        }
    }

    public static DiscoverySettings defaults() {
        return new DiscoverySettings(false, 7, 3, CandidateProofStatus.VALIDATED_BY_EXAMPLES);
    }

    public static DiscoverySettings collectingEquivalentPaths() {
        return new DiscoverySettings(true, 7, 3, CandidateProofStatus.VALIDATED_BY_EXAMPLES);
    }
}
