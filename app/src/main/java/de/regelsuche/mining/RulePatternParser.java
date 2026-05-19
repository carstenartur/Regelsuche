package de.regelsuche.mining;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;

public class RulePatternParser {
    private final ExpressionParser parser = new ExpressionParser();

    public RulePatternNode parse(String pattern) {
        return convert(parser.parseTerm(pattern));
    }

    private RulePatternNode convert(Expr expression) {
        if (expression instanceof NumberExpr numberExpr) {
            if (Math.rint(numberExpr.value()) != numberExpr.value()) {
                throw new IllegalArgumentException("Only integer pattern numbers are supported: " + numberExpr.value());
            }
            return new PatternNumber((int) numberExpr.value());
        }
        if (expression instanceof VariableExpr variableExpr) {
            return new PatternVariable(variableExpr.name());
        }
        BinaryExpr binaryExpr = (BinaryExpr) expression;
        return new PatternBinary(convert(binaryExpr.left()), binaryExpr.operator(), convert(binaryExpr.right()));
    }
}
