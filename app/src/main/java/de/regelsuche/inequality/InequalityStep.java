package de.regelsuche.inequality;

import de.regelsuche.assumption.Assumption;
import java.util.List;
import java.util.Objects;

/**
 * Single step of an inequality rewrite: the rule id, the resulting
 * {@link Inequality}, a human readable description, and any side conditions
 * (assumptions) introduced by the step.
 */
public record InequalityStep(
    String ruleId,
    Inequality inequality,
    String description,
    List<Assumption> assumptions
) {
    public InequalityStep {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(inequality, "inequality");
        Objects.requireNonNull(description, "description");
        assumptions = List.copyOf(assumptions == null ? List.of() : assumptions);
    }
}
