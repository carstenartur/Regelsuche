package de.regelsuche.mining;

/** Rewards candidates that survived counterexample search and penalizes refutations. */
public final class CounterexampleRobustnessScore implements InterestingnessScoringModule {
    @Override
    public String name() {
        return "counterexampleRobustness";
    }

    @Override
    public double score(InterestingnessScoringContext context) {
        if (context.candidate().counterexampleSearchStatus() != null) {
            return switch (context.candidate().counterexampleSearchStatus()) {
                case COUNTEREXAMPLE_FOUND -> -2.0;
                case NO_COUNTEREXAMPLE_FOUND -> 1.0;
                case INCONCLUSIVE -> 0.0;
            };
        }
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
