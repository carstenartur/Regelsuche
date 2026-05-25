package de.regelsuche.mining;

/** Immutable inputs shared by interestingness scoring modules. */
public record InterestingnessScoringContext(
    HypothesisCandidate candidate,
    double knownRuleSimilarity
) {
    public InterestingnessScoringContext {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate must not be null");
        }
        knownRuleSimilarity = Math.max(0.0, Math.min(1.0, knownRuleSimilarity));
    }
}
