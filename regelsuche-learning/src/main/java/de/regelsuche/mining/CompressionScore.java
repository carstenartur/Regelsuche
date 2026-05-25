package de.regelsuche.mining;

/** Rewards hypotheses that compress long successful paths into one reusable macro. */
public final class CompressionScore implements InterestingnessScoringModule {
    @Override
    public String name() {
        return "compression";
    }

    @Override
    public double score(InterestingnessScoringContext context) {
        HypothesisCandidate candidate = context.candidate();
        double pathCompression = candidate.supportingPaths().stream()
            .mapToInt(CompressionScore::estimatedPathLength)
            .average()
            .stream()
            .map(length -> Math.log1p(Math.max(0.0, length - 1.0)))
            .findFirst()
            .orElse(0.0);
        int leftComplexity = structuralComplexity(candidate.leftPattern());
        int rightComplexity = structuralComplexity(candidate.rightPattern());
        double expressionCompression = Math.max(0.0, leftComplexity - rightComplexity) / 4.0;
        return pathCompression + expressionCompression;
    }

    private static int estimatedPathLength(String pathId) {
        if (pathId == null || pathId.isBlank()) {
            return 1;
        }
        return Math.max(1, pathId.split(">|/|:", -1).length);
    }

    private static int structuralComplexity(String expression) {
        if (expression == null || expression.isBlank()) {
            return 0;
        }
        int tokens = 0;
        for (int i = 0; i < expression.length(); i++) {
            char ch = expression.charAt(i);
            if ("+-*/^(),".indexOf(ch) >= 0 || Character.isLetterOrDigit(ch)) {
                tokens++;
            }
        }
        return tokens;
    }
}
