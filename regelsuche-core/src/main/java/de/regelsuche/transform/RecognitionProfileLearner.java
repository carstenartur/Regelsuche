package de.regelsuche.transform;

import de.regelsuche.ast.Expr;
import java.util.List;
import java.util.Objects;

/**
 * Selects the narrowest built-in recognition profile that accepts all positive
 * examples and rejects all negative examples for a pattern rule.
 */
public final class RecognitionProfileLearner {
    private static final List<RecognitionProfile> CANDIDATES = List.of(
        RecognitionProfile.exact(),
        RecognitionProfile.arithmeticAc(),
        RecognitionProfile.algebraicAc()
    );

    public RecognitionProfile learn(PatternExpr pattern, List<Expr> positives, List<Expr> negatives) {
        Objects.requireNonNull(pattern, "pattern");
        positives = positives == null ? List.of() : List.copyOf(positives);
        negatives = negatives == null ? List.of() : List.copyOf(negatives);
        if (positives.isEmpty()) {
            throw new IllegalArgumentException("at least one positive example is required");
        }
        for (RecognitionProfile candidate : CANDIDATES) {
            if (acceptsAll(pattern, positives, candidate) && rejectsAll(pattern, negatives, candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("no safe built-in recognition profile separates the examples");
    }

    private static boolean acceptsAll(PatternExpr pattern, List<Expr> examples, RecognitionProfile profile) {
        return examples.stream().allMatch(example ->
            EquivalenceAwarePatternMatcher.match(pattern, example, new java.util.HashMap<>(), profile));
    }

    private static boolean rejectsAll(PatternExpr pattern, List<Expr> examples, RecognitionProfile profile) {
        return examples.stream().noneMatch(example ->
            EquivalenceAwarePatternMatcher.match(pattern, example, new java.util.HashMap<>(), profile));
    }
}
