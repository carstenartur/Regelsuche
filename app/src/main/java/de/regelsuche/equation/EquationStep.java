package de.regelsuche.equation;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Result of applying a single equation transformation.
 */
public record EquationStep(
    String ruleId,
    de.regelsuche.ast.Equation equation,
    String description,
    List<Assumption> assumptions
) {
    public EquationStep {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(equation, "equation");
        Objects.requireNonNull(description, "description");
        assumptions = List.copyOf(Objects.requireNonNullElse(assumptions, List.of()));
    }

    public String formattedEquation() {
        return ExpressionFormatter.format(equation);
    }
}
