package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import java.util.List;
import java.util.Objects;

/** Selected search edges, not a reconstructed algebra story or a complete primitive expansion. */
public record SearchDerivation(VerificationEvidence evidence, int candidateLimit, long generationBudget, List<Step> steps) {
    public static final int MAX_STEPS = 64;
    public record Step(String rule, Expr before, Expr after) {
        public Step {
            if (rule == null || rule.isBlank() || rule.length() > 1024)
                throw new IllegalArgumentException("INVALID_DERIVATION_RULE");
            Objects.requireNonNull(before); Objects.requireNonNull(after);
        }
    }
    public SearchDerivation {
        Objects.requireNonNull(evidence);
        steps = List.copyOf(steps);
        if (steps.size() > MAX_STEPS || candidateLimit < 1 || candidateLimit > 1024 || generationBudget < 1)
            throw new IllegalArgumentException("DERIVATION_STRUCTURAL_BOUND");
    }
}
