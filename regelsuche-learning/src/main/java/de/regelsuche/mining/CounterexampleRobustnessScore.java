package de.regelsuche.mining;

/** Rewards candidates that survived counterexample search and penalizes refutations. */
public final class CounterexampleRobustnessScore implements InterestingnessScoringModule {
    @Override
    public String name() {
        return "counterexampleRobustness";
    }

    @Override
    public double score(InterestingnessScoringContext context) {
        Boolean found = context.candidate().counterexampleStatus();
        if (Boolean.TRUE.equals(found)) {
            return -2.0;
        }
        if (Boolean.FALSE.equals(found)) {
            return 1.0;
        }
        return 0.25;
    }
}
