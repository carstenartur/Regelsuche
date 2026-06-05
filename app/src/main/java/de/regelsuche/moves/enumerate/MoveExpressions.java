package de.regelsuche.moves.enumerate;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Small shared AST helpers used by the parameter enumerators. */
final class MoveExpressions {

    private static final ExpressionParser PARSER = new ExpressionParser();

    private MoveExpressions() {
    }

    /** Parses an expression, returning empty when it cannot be parsed. */
    static Optional<Expr> parse(String expression) {
        if (expression == null || expression.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(PARSER.parseTerm(expression));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** Formats an expression node back into its infix string form. */
    static String format(Expr expr) {
        return ExpressionFormatter.format(expr);
    }

    /** Collects every subexpression node (including the root) in a stable order. */
    static List<Expr> subexpressions(Expr root) {
        List<Expr> collected = new ArrayList<>();
        collect(root, collected);
        return collected;
    }

    private static void collect(Expr expr, List<Expr> out) {
        if (expr == null) {
            return;
        }
        out.add(expr);
        if (expr instanceof BinaryExpr binary) {
            collect(binary.left(), out);
            collect(binary.right(), out);
        } else if (expr instanceof FunctionExpr function) {
            for (Expr argument : function.arguments()) {
                collect(argument, out);
            }
        }
    }
}
