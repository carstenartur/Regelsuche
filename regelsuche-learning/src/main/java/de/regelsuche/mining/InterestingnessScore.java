package de.regelsuche.mining;

import de.regelsuche.validation.CandidateProofStatus;

/** Composite ranking for deciding which hypotheses deserve report/inventory attention first. */
public record InterestingnessScore(
    double compressionGain,
    double generality,
    double independentEvidenceCount,
    double macroReusability,
    double proofStatus,
    double counterexampleStatus,
    double similarityToKnownRules,
    double crossDomainRecurrence,
    double minimalAssumptions,
    double total
) implements Comparable<InterestingnessScore> {
    @Override
    public int compareTo(InterestingnessScore other) {
        return Double.compare(other.total, total);
    }

    public static InterestingnessScore from(HypothesisCandidate candidate, double similarityToKnownRules) {
        double evidence = distinctEvidence(candidate);
        double compression = Math.max(0.0, candidate.leftPattern().length() - candidate.rightPattern().length()) / 20.0;
        double generality = candidate.expressionPlaceholders().size() + placeholderCount(candidate.leftPattern());
        double reusability = Math.log1p(candidate.supportingPaths().size());
        double proof = proofWeight(candidate.proofStatus());
        double counterexample = Boolean.TRUE.equals(candidate.counterexampleStatus()) ? -2.0 : 1.0;
        double crossDomain = candidate.supportingPaths().stream().map(path -> path.split("[:/#-]", 2)[0]).distinct().count();
        double assumptions = 1.0 / (1.0 + candidate.assumptions().size());
        double knownPenalty = Math.max(0.0, Math.min(1.0, similarityToKnownRules));
        double total = compression + generality + evidence + reusability + proof + counterexample
            + crossDomain + assumptions - knownPenalty;
        return new InterestingnessScore(compression, generality, evidence, reusability, proof,
            counterexample, knownPenalty, crossDomain, assumptions, total);
    }

    private static double distinctEvidence(HypothesisCandidate candidate) {
        return candidate.supportingExpressions().isEmpty()
            ? candidate.supportingPaths().size()
            : candidate.supportingExpressions().stream().distinct().count();
    }

    private static double placeholderCount(String pattern) {
        return pattern.chars().filter(Character::isUpperCase).distinct().count();
    }

    private static double proofWeight(CandidateProofStatus status) {
        return status == null ? 0.0 : status.ordinal() / 2.0;
    }
}
