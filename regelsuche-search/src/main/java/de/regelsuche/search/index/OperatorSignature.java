package de.regelsuche.search.index;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;

record OperatorSignature(String rootSymbol, int arity) {
    static OperatorSignature of(Expr expr) {
        if (expr instanceof BinaryExpr binary) {
            return new OperatorSignature(binary.operator().symbol(), 2);
        }
        if (expr instanceof FunctionExpr function) {
            return new OperatorSignature(function.name().toLowerCase(java.util.Locale.ROOT), function.arguments().size());
        }
        if (expr instanceof NumberExpr) {
            return new OperatorSignature("num", 0);
        }
        if (expr instanceof VariableExpr variable) {
            return new OperatorSignature(variable.name().toLowerCase(java.util.Locale.ROOT), 0);
        }
        return new OperatorSignature("", 0);
    }

    static OperatorSignature parse(String expression) {
        try {
            return of(new ExpressionParser().parseTerm(expression == null ? "" : expression));
        } catch (RuntimeException ignored) {
            return new OperatorSignature(RootSymbolTermRuleIndex.rootSymbol(expression), -1);
        }
    }

    boolean compatibleWith(OperatorSignature query) {
        return query != null
            && rootSymbol.equals(query.rootSymbol)
            && (arity < 0 || query.arity < 0 || arity == query.arity);
    }
}
