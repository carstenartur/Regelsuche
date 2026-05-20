package de.regelsuche.equation;

import de.regelsuche.ast.Expr;
import java.util.List;
import java.util.Objects;

public record EquationRewriteContext(List<Expr> candidateOperands, List<String> injectiveFunctions) {
    public EquationRewriteContext {
        Objects.requireNonNull(candidateOperands, "candidateOperands");
        Objects.requireNonNull(injectiveFunctions, "injectiveFunctions");
        candidateOperands = List.copyOf(candidateOperands);
        injectiveFunctions = List.copyOf(injectiveFunctions);
    }

    public static EquationRewriteContext defaults() {
        return new EquationRewriteContext(List.of(), List.of("exp"));
    }
}
