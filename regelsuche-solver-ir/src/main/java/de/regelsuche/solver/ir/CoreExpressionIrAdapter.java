package de.regelsuche.solver.ir;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.solver.ir.SolverIr.Binary;
import de.regelsuche.solver.ir.SolverIr.Call;
import de.regelsuche.solver.ir.SolverIr.Expression;
import de.regelsuche.solver.ir.SolverIr.Literal;
import de.regelsuche.solver.ir.SolverIr.Symbol;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Lossless adapter between the core algebra AST and the public solver IR. */
public final class CoreExpressionIrAdapter {
    private final ExpressionParser parser = new ExpressionParser();

    public Expression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression must not be blank");
        }
        return toIr(parser.parseTerm(expression));
    }

    public Expression toIr(Expr expression) {
        Objects.requireNonNull(expression, "expression");
        if (expression instanceof NumberExpr number) {
            return new Literal(numberLiteral(number.value()));
        }
        if (expression instanceof VariableExpr variable) {
            return new Symbol(variable.name());
        }
        if (expression instanceof BinaryExpr binary) {
            return new Binary(
                toIrOperator(binary.operator()),
                toIr(binary.left()),
                toIr(binary.right()));
        }
        if (expression instanceof FunctionExpr function) {
            return new Call(
                function.name(),
                function.arguments().stream().map(this::toIr).toList());
        }
        throw new IllegalArgumentException("unsupported core expression: " + expression);
    }

    public Expr toCore(Expression expression) {
        Objects.requireNonNull(expression, "expression");
        if (expression instanceof Literal literal) {
            return new NumberExpr(Double.parseDouble(literal.value()));
        }
        if (expression instanceof Symbol symbol) {
            return new VariableExpr(symbol.name());
        }
        if (expression instanceof Binary binary) {
            return new BinaryExpr(
                toCore(binary.left()),
                toCoreOperator(binary.operator()),
                toCore(binary.right()));
        }
        if (expression instanceof Call call) {
            List<Expr> arguments = call.arguments().stream().map(this::toCore).toList();
            return new FunctionExpr(call.function(), arguments);
        }
        throw new IllegalArgumentException("unsupported solver expression: " + expression);
    }

    public String render(Expression expression) {
        return ExpressionFormatter.format(toCore(expression));
    }

    private static SolverIr.BinaryOperator toIrOperator(BinaryOperator operator) {
        return switch (operator) {
            case ADD -> SolverIr.BinaryOperator.ADD;
            case SUB -> SolverIr.BinaryOperator.SUBTRACT;
            case MUL -> SolverIr.BinaryOperator.MULTIPLY;
            case DIV -> SolverIr.BinaryOperator.DIVIDE;
            case POW -> SolverIr.BinaryOperator.POWER;
        };
    }

    private static BinaryOperator toCoreOperator(SolverIr.BinaryOperator operator) {
        return switch (operator) {
            case ADD -> BinaryOperator.ADD;
            case SUBTRACT -> BinaryOperator.SUB;
            case MULTIPLY -> BinaryOperator.MUL;
            case DIVIDE -> BinaryOperator.DIV;
            case POWER -> BinaryOperator.POW;
        };
    }

    private static String numberLiteral(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("non-finite numbers are not supported");
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
