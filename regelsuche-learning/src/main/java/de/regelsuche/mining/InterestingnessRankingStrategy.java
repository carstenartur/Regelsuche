package de.regelsuche.mining;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Default ranking: novelty, universality, confidence, assumptions, and cross-domain recurrence. */
public final class InterestingnessRankingStrategy implements HypothesisRankingStrategy {
    @Override
    public List<RankedHypothesis> rank(
        List<HypothesisCandidate> hypotheses,
        Map<String, Double> similarityToKnownRules,
        Map<String, Set<String>> domainTagsByHypothesisId
    ) {
        if (hypotheses == null || hypotheses.isEmpty()) {
            return List.of();
        }
        Map<String, Double> similarities = similarityToKnownRules == null ? Map.of() : similarityToKnownRules;
        Map<String, Set<String>> domains = domainTagsByHypothesisId == null ? Map.of() : domainTagsByHypothesisId;
        return hypotheses.stream()
            .map(hypothesis -> new RankedHypothesis(
                hypothesis,
                InterestingnessScore.from(
                    hypothesis,
                    similarities.getOrDefault(hypothesis.id(), 0.0),
                    domains.getOrDefault(hypothesis.id(), Set.of())
                )
            ))
            .sorted(Comparator.naturalOrder())
            .toList();
    }
}
