package de.regelsuche.calculus;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.Objects;
import java.util.Optional;

/**
 * Lightweight integrator: covers the elementary closed-form rules that pair
 * with {@link Differentiator}.
 *
 * <p>Currently supported:</p>
 * <ul>
 *   <li>constant: {@code ∫ c dx = c*x},</li>
 *   <li>variable: {@code ∫ x dx = x^2 / 2},</li>
 *   <li>power (constant exponent {@code n != -1}): {@code ∫ x^n dx = x^(n+1)/(n+1)},</li>
 *   <li>{@code ∫ 1/x dx = ln(x)},</li>
 *   <li>{@code ∫ exp(x) dx = exp(x)}, {@code ∫ sin(x) dx = -cos(x)}, {@code ∫ cos(x) dx = sin(x)},</li>
 *   <li>linearity: {@code ∫ (f + g) dx = ∫f + ∫g}, {@code ∫ c*f dx = c * ∫f}.</li>
 * </ul>
 *
 * <p>For expressions outside this set the integrator returns
 * {@link Optional#empty()} rather than guessing. The integration constant
 * {@code +C} is intentionally omitted — callers that need it can append it
 * when rendering.</p>
 */
public final class Integrator {

    public Optional<Expr> integrate(Expr expr, String variable) {
        Objects.requireNonNull(expr, "expr");
        Objects.requireNonNull(variable, "variable");
        Optional<Expr> result = integrateInternal(expr, variable);
        return result.map(Differentiator::simplify);
    }

    private Optional<Expr> integrateInternal(Expr expr, String variable) {
        if (expr instanceof NumberExpr number) {
            return Optional.of(new BinaryExpr(new NumberExpr(number.value()), BinaryOperator.MUL, new VariableExpr(variable)));
        }
        if (expr instanceof VariableExpr varExpr) {
            if (varExpr.name().equals(variable)) {
                return Optional.of(new BinaryExpr(
                    new BinaryExpr(new VariableExpr(variable), BinaryOperator.POW, new NumberExpr(2)),
                    BinaryOperator.DIV,
                    new NumberExpr(2)
                ));
            }
            return Optional.of(new BinaryExpr(varExpr, BinaryOperator.MUL, new VariableExpr(variable)));
        }
        if (expr instanceof BinaryExpr binary) {
            return switch (binary.operator()) {
                case ADD -> combine(binary.left(), binary.right(), variable, BinaryOperator.ADD);
                case SUB -> combine(binary.left(), binary.right(), variable, BinaryOperator.SUB);
                case MUL -> integrateProduct(binary.left(), binary.right(), variable);
                case POW -> integratePower(binary.left(), binary.right(), variable);
                case DIV -> integrateDivision(binary.left(), binary.right(), variable);
            };
        }
        if (expr instanceof FunctionExpr fn && fn.arguments().size() == 1
            && fn.arguments().get(0) instanceof VariableExpr varExpr
            && varExpr.name().equals(variable)) {
            return switch (fn.name()) {
                case "sin" -> Optional.of(new BinaryExpr(
                    new NumberExpr(0),
                    BinaryOperator.SUB,
                    new FunctionExpr("cos", varExpr)
                ));
                case "cos" -> Optional.of(new FunctionExpr("sin", varExpr));
                case "exp" -> Optional.of(new FunctionExpr("exp", varExpr));
                default -> Optional.empty();
            };
        }
        return Optional.empty();
    }

    private Optional<Expr> combine(Expr left, Expr right, String variable, BinaryOperator operator) {
        Optional<Expr> l = integrateInternal(left, variable);
        Optional<Expr> r = integrateInternal(right, variable);
        if (l.isEmpty() || r.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BinaryExpr(l.get(), operator, r.get()));
    }

    private Optional<Expr> integrateProduct(Expr left, Expr right, String variable) {
        if (isConstantWith(left, variable)) {
            return integrateInternal(right, variable).map(integrated ->
                new BinaryExpr(left, BinaryOperator.MUL, integrated));
        }
        if (isConstantWith(right, variable)) {
            return integrateInternal(left, variable).map(integrated ->
                new BinaryExpr(right, BinaryOperator.MUL, integrated));
        }
        return Optional.empty();
    }

    private Optional<Expr> integratePower(Expr base, Expr exponent, String variable) {
        if (!(base instanceof VariableExpr varExpr) || !varExpr.name().equals(variable)) {
            return Optional.empty();
        }
        if (!(exponent instanceof NumberExpr n)) {
            return Optional.empty();
        }
        double power = n.value();
        if (power == -1.0) {
            return Optional.of(new FunctionExpr("ln", new FunctionExpr("abs", varExpr)));
        }
        return Optional.of(new BinaryExpr(
            new BinaryExpr(varExpr, BinaryOperator.POW, new NumberExpr(power + 1)),
            BinaryOperator.DIV,
            new NumberExpr(power + 1)
        ));
    }

    private Optional<Expr> integrateDivision(Expr left, Expr right, String variable) {
        if (left instanceof NumberExpr number
            && right instanceof VariableExpr varExpr
            && varExpr.name().equals(variable)) {
            return Optional.of(new BinaryExpr(
                new NumberExpr(number.value()),
                BinaryOperator.MUL,
                new FunctionExpr("ln", new FunctionExpr("abs", varExpr))
            ));
        }
        if (isConstantWith(right, variable)) {
            return integrateInternal(left, variable).map(integrated ->
                new BinaryExpr(integrated, BinaryOperator.DIV, right));
        }
        return Optional.empty();
    }

    private boolean isConstantWith(Expr expr, String variable) {
        if (expr instanceof NumberExpr) {
            return true;
        }
        if (expr instanceof VariableExpr varExpr) {
            return !varExpr.name().equals(variable);
        }
        if (expr instanceof BinaryExpr binary) {
            return isConstantWith(binary.left(), variable)
                && isConstantWith(binary.right(), variable);
        }
        if (expr instanceof FunctionExpr fn) {
            return fn.arguments().stream().allMatch(arg -> isConstantWith(arg, variable));
        }
        return false;
    }
}
