package de.regelsuche.transform;

import de.regelsuche.ast.Expr;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Matches a pattern against any bounded representative supplied by an e-class provider. */
public final class EquivalenceClassPatternMatcher {
    public MatchResult match(
        PatternExpr pattern,
        Expr expression,
        RecognitionProfile profile,
        EquivalentExpressionProvider provider
    ) {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(provider, "provider");
        int index = 0;
        for (Expr representative : provider.representatives(expression, profile)) {
            Map<String, Expr> bindings = new HashMap<>();
            if (EquivalenceAwarePatternMatcher.match(pattern, representative, bindings, profile)) {
                return new MatchResult(true, representative, Map.copyOf(bindings), index);
            }
            index++;
        }
        return new MatchResult(false, null, Map.of(), -1);
    }

    public record MatchResult(
        boolean matched,
        Expr representative,
        Map<String, Expr> bindings,
        int representativeIndex
    ) {
    }
}
