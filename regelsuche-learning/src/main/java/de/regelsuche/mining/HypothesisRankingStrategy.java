package de.regelsuche.mining;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pluggable ordering for mined and synthesis-proposed hypotheses. */
@FunctionalInterface
public interface HypothesisRankingStrategy {
    List<RankedHypothesis> rank(
        List<HypothesisCandidate> hypotheses,
        Map<String, Double> similarityToKnownRules,
        Map<String, Set<String>> domainTagsByHypothesisId
    );
}
