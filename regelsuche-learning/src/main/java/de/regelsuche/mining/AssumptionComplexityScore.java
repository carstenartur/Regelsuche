package de.regelsuche.mining;

/** Rewards hypotheses with fewer and weaker assumptions. */
public final class AssumptionComplexityScore implements InterestingnessScoringModule {
    @Override
    public String name() {
        return "assumptionComplexity";
    }

    @Override
    public double score(InterestingnessScoringContext context) {
        double penalty = 0.0;
        for (String assumption : context.candidate().assumptions()) {
            penalty += strengthPenalty(assumption);
        }
        return 1.0 / (1.0 + penalty);
    }

    private static double strengthPenalty(String assumption) {
        if (assumption == null || assumption.isBlank()) {
            return 0.0;
        }
        String compact = assumption.replaceAll("\\s+", "");
        if (compact.contains("!=") || compact.contains("≠")) {
            return 0.75;
        }
        if (compact.contains(">=") || compact.contains("<=")) {
            return 1.0;
        }
        if (compact.contains(">") || compact.contains("<") || compact.contains("=")) {
            return 1.25;
        }
        return 1.0;
    }
}
