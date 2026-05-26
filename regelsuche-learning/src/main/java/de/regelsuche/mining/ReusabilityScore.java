package de.regelsuche.mining;

/** Rewards discoveries that replay across many future or historical searches. */
public final class ReusabilityScore implements InterestingnessScoringModule {
    @Override
    public String name() {
        return "reusability";
    }

    @Override
    public double score(InterestingnessScoringContext context) {
        HypothesisCandidate candidate = context.candidate();
        long paths = candidate.supportingPaths().stream().distinct().count();
        long witnesses = candidate.supportingExpressions().stream().distinct().count();
        double replayUsefulness = Math.log1p(paths);
        double witnessUsefulness = Math.log1p(witnesses) / 2.0;
        return replayUsefulness + witnessUsefulness;
    }
}
