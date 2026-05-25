package de.regelsuche.mining;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RulePatternInstantiator {
    public Expr instantiate(RulePatternNode pattern, Map<String, Expr> bindings) {
        if (pattern instanceof PatternNumber number) {
            return new NumberExpr(number.value());
        }
        if (pattern instanceof PatternVariable variable) {
            return bindings.getOrDefault(variable.name(), new VariableExpr(variable.name()));
        }
        if (pattern instanceof PatternFunction function) {
            List<Expr> args = new ArrayList<>(function.arguments().size());
            for (RulePatternNode argument : function.arguments()) {
                args.add(instantiate(argument, bindings));
            }
            return new FunctionExpr(function.name(), args);
        }
        PatternBinary binary = (PatternBinary) pattern;
        return new BinaryExpr(
            instantiate(binary.left(), bindings),
            binary.op(),
            instantiate(binary.right(), bindings)
        );
    }
}
