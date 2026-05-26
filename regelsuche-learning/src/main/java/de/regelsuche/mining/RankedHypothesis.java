package de.regelsuche.mining;

/** Hypothesis plus the composite score used for deterministic prioritization. */
public record RankedHypothesis(
    HypothesisCandidate hypothesis,
    InterestingnessScore score
) implements Comparable<RankedHypothesis> {
    public RankedHypothesis {
        if (hypothesis == null) {
            throw new IllegalArgumentException("hypothesis must not be null");
        }
        if (score == null) {
            throw new IllegalArgumentException("score must not be null");
        }
    }

    @Override
    public int compareTo(RankedHypothesis other) {
        return score.compareTo(other.score);
    }
}
