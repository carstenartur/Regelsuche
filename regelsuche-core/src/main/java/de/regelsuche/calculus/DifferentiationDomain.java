package de.regelsuche.calculus;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionContext;
import de.regelsuche.assumption.ExpressionDefinedness;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import java.util.List;

/** Sufficient, explicitly retained domains for the elementary derivative rules. */
final class DifferentiationDomain {
    private DifferentiationDomain() {
    }

    static List<Assumption> assumptions(Expr body, String variable) {
        AssumptionContext context = new AssumptionContext();
        collect(body, variable, context);
        return context.snapshot();
    }

    private static void collect(Expr body, String variable, AssumptionContext context) {
        boolean domainRetained = ExpressionDefinedness.canElideOnPositivePowerDomain(body, context);
        if (domainRetained && elementary(body)) {
            return;
        }
        // The sum/product may be differentiable even where an operand is not
        // (abs(x) - abs(x) at zero). Splitting needs the stronger operand guards.
        if (body instanceof BinaryExpr binary
                && (binary.operator() == BinaryOperator.ADD
                    || binary.operator() == BinaryOperator.SUB
                    || binary.operator() == BinaryOperator.MUL)) {
            collect(binary.left(), variable, context);
            collect(binary.right(), variable, context);
            return;
        }
        String expression = ExpressionFormatter.format(body);
        context.add(Assumption.customPredicate(
            "differentiable(" + expression + ", " + variable + ")",
            List.of(expression, variable)));
    }

    private static boolean elementary(Expr expression) {
        if (expression instanceof NumberExpr || expression instanceof VariableExpr) {
            return true;
        }
        if (expression instanceof BinaryExpr binary) {
            return elementary(binary.left()) && elementary(binary.right());
        }
        if (expression instanceof FunctionExpr function && function.arguments().size() == 1) {
            return switch (function.name()) {
                case "sin", "cos", "exp", "ln", "log" -> elementary(function.argument());
                default -> false;
            };
        }
        return false;
    }
}
