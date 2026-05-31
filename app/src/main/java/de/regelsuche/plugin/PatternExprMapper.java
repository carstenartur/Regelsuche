package de.regelsuche.plugin;

import de.regelsuche.mining.PatternBinary;
import de.regelsuche.mining.PatternFunction;
import de.regelsuche.mining.PatternNumber;
import de.regelsuche.mining.PatternVariable;
import de.regelsuche.mining.RulePatternNode;
import de.regelsuche.transform.PatternExpr;

final class PatternExprMapper {
    private PatternExprMapper() {
    }

    static PatternExpr toPatternExpr(RulePatternNode node) {
        if (node instanceof PatternNumber number) {
            return PatternExpr.num(number.value());
        }
        if (node instanceof PatternVariable variable) {
            return PatternExpr.var(variable.name());
        }
        if (node instanceof PatternFunction function) {
            PatternExpr[] converted = new PatternExpr[function.arguments().size()];
            for (int i = 0; i < converted.length; i++) {
                converted[i] = toPatternExpr(function.arguments().get(i));
            }
            return PatternExpr.fn(function.name(), converted);
        }
        PatternBinary binary = (PatternBinary) node;
        return PatternExpr.op(binary.op(), toPatternExpr(binary.left()), toPatternExpr(binary.right()));
    }
}
