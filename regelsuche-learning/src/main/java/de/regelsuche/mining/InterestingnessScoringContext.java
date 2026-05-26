package de.regelsuche.mining;

import java.util.Set;

/** Immutable inputs shared by interestingness scoring modules. */
public record InterestingnessScoringContext(
    HypothesisCandidate candidate,
    double knownRuleSimilarity,
    Set<String> domainTags
) {
    public InterestingnessScoringContext {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate must not be null");
        }
        knownRuleSimilarity = Math.max(0.0, Math.min(1.0, knownRuleSimilarity));
        domainTags = domainTags == null ? Set.of() : Set.copyOf(domainTags);
    }

    public InterestingnessScoringContext(HypothesisCandidate candidate, double knownRuleSimilarity) {
        this(candidate, knownRuleSimilarity, Set.of());
    }
}
