package de.regelsuche.mining;

import java.util.List;
import java.util.Set;

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
    private static final CompressionScore COMPRESSION = new CompressionScore();
    private static final GeneralizationScore GENERALIZATION = new GeneralizationScore();
    private static final ReusabilityScore REUSABILITY = new ReusabilityScore();
    private static final SurpriseScore SURPRISE = new SurpriseScore();
    private static final CrossDomainScore CROSS_DOMAIN = new CrossDomainScore();
    private static final AssumptionComplexityScore ASSUMPTIONS = new AssumptionComplexityScore();
    private static final ProofConfidenceScore PROOF = new ProofConfidenceScore();
    private static final CounterexampleRobustnessScore COUNTEREXAMPLES = new CounterexampleRobustnessScore();
    private static final List<InterestingnessScoringModule> DEFAULT_MODULES = List.of(
        COMPRESSION,
        GENERALIZATION,
        REUSABILITY,
        SURPRISE,
        CROSS_DOMAIN,
        ASSUMPTIONS,
        PROOF,
        COUNTEREXAMPLES
    );

    @Override
    public int compareTo(InterestingnessScore other) {
        return Double.compare(other.total, total);
    }

    public static InterestingnessScore from(HypothesisCandidate candidate, KnownRuleRepository knownRules) {
        double similarity = new KnownRuleSimilarityService()
            .similarityToKnownRules(candidate.leftPattern(), candidate.rightPattern(), knownRules);
        return from(candidate, similarity);
    }

    public static InterestingnessScore from(HypothesisCandidate candidate, double similarityToKnownRules) {
        return from(candidate, similarityToKnownRules, Set.of());
    }

    public static InterestingnessScore from(
        HypothesisCandidate candidate,
        double similarityToKnownRules,
        Set<String> domainTags
    ) {
        InterestingnessScoringContext context =
            new InterestingnessScoringContext(candidate, similarityToKnownRules, domainTags);
        double evidence = distinctEvidence(candidate);
        double compression = COMPRESSION.score(context);
        double generality = GENERALIZATION.score(context);
        double reusability = REUSABILITY.score(context);
        double proof = PROOF.score(context);
        double counterexample = COUNTEREXAMPLES.score(context);
        double crossDomain = CROSS_DOMAIN.score(context);
        double assumptions = ASSUMPTIONS.score(context);
        double surprise = SURPRISE.score(context);
        double knownPenalty = Math.max(0.0, Math.min(1.0, similarityToKnownRules));
        double total = weightedTotal(
            compression,
            generality,
            evidence,
            reusability,
            surprise,
            crossDomain,
            assumptions,
            proof,
            counterexample
        ) - knownPenalty;
        return new InterestingnessScore(compression, generality, evidence, reusability, proof,
            counterexample, knownPenalty, crossDomain, assumptions, total);
    }

    public static List<InterestingnessScoringModule> defaultModules() {
        return DEFAULT_MODULES;
    }

    private static double weightedTotal(
        double compression,
        double generality,
        double evidence,
        double reusability,
        double surprise,
        double crossDomain,
        double assumptions,
        double proof,
        double counterexample
    ) {
        return 1.25 * compression
            + 1.10 * generality
            + 0.75 * evidence
            + 1.20 * reusability
            + 1.00 * surprise
            + 1.10 * crossDomain
            + 0.80 * assumptions
            + 1.00 * proof
            + 1.50 * counterexample;
    }

    private static double distinctEvidence(HypothesisCandidate candidate) {
        return candidate.supportingExpressions().isEmpty()
            ? candidate.supportingPaths().size()
            : candidate.supportingExpressions().stream().distinct().count();
    }

}
