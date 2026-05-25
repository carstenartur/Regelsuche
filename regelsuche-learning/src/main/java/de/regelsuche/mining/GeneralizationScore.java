package de.regelsuche.mining;

import java.util.regex.Pattern;

/** Rewards broad placeholder structure and independently witnessed abstractions. */
public final class GeneralizationScore implements InterestingnessScoringModule {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b");

    @Override
    public String name() {
        return "generalization";
    }

    @Override
    public double score(InterestingnessScoringContext context) {
        HypothesisCandidate candidate = context.candidate();
        long textualPlaceholders = PLACEHOLDER.matcher(candidate.leftPattern() + " " + candidate.rightPattern())
            .results()
            .map(match -> match.group())
            .distinct()
            .count();
        int expressionPlaceholders = candidate.expressionPlaceholders().size();
        long witnesses = candidate.supportingExpressions().stream().distinct().count();
        return textualPlaceholders + expressionPlaceholders + Math.log1p(witnesses);
    }
}
