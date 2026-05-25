package de.regelsuche.mining;

/** Rewards hypotheses that are novel relative to the known rule base. */
public final class SurpriseScore implements InterestingnessScoringModule {
    @Override
    public String name() {
        return "surprise";
    }

    @Override
    public double score(InterestingnessScoringContext context) {
        return Math.max(0.0, context.candidate().noveltyScore()) + (1.0 - context.knownRuleSimilarity());
    }
}
