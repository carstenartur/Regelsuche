package de.regelsuche.search.program;

import de.regelsuche.ast.*;
import java.util.List;

/** Shallow exact identity: child IDs never invoke recursive expression hashing. */
record ComputationStructuralKey(String kind, Object value, List<Integer> arguments) {
    ComputationStructuralKey { arguments = List.copyOf(arguments); }
    static ComputationStructuralKey of(Expr expression, List<Integer> arguments) {
        if (expression instanceof VariableExpr v) return new ComputationStructuralKey("variable", v.name(), arguments);
        if (expression instanceof NumberExpr n) return new ComputationStructuralKey("number", n.value(), arguments);
        if (expression instanceof BinaryExpr b) return new ComputationStructuralKey("binary", b.operator(), arguments);
        return new ComputationStructuralKey("function", ((FunctionExpr) expression).name(), arguments);
    }
}
