package de.regelsuche.mining;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.Map;

public class RulePatternInstantiator {
    public Expr instantiate(RulePatternNode pattern, Map<String, Expr> bindings) {
        if (pattern instanceof PatternNumber number) {
            return new NumberExpr(number.value());
        }
        if (pattern instanceof PatternVariable variable) {
            return bindings.getOrDefault(variable.name(), new VariableExpr(variable.name()));
        }
        PatternBinary binary = (PatternBinary) pattern;
        return new BinaryExpr(
            instantiate(binary.left(), bindings),
            binary.op(),
            instantiate(binary.right(), bindings)
        );
    }
}
