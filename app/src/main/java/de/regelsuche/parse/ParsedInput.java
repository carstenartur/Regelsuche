package de.regelsuche.parse;

import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import java.util.List;

public record ParsedInput(List<Expr> terms, List<Equation> equations) {
    public ParsedInput {
        terms = terms == null ? List.of() : List.copyOf(terms);
        equations = equations == null ? List.of() : List.copyOf(equations);
    }
}
