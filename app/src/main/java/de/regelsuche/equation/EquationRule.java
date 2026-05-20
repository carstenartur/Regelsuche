package de.regelsuche.equation;

import de.regelsuche.ast.Equation;
import java.util.List;

public interface EquationRule {
    String id();

    String description();

    List<EquationStep> apply(Equation equation, EquationRewriteContext context);
}
