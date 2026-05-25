package de.regelsuche.calculus;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.List;
import java.util.Objects;

/**
 * Symbolic differentiator for the AST.
 *
 * <p>Implements the elementary rules:</p>
 * <ul>
 *   <li>constant rule: {@code d/dx c = 0},</li>
 *   <li>variable rule: {@code d/dx x = 1}, {@code d/dx y = 0} for {@code y != x},</li>
 *   <li>sum/difference rule: {@code d/dx (f ± g) = f' ± g'},</li>
 *   <li>product rule: {@code d/dx (f * g) = f'*g + f*g'},</li>
 *   <li>quotient rule: {@code d/dx (f / g) = (f'*g - f*g') / g^2},</li>
 *   <li>power rule (constant exponent {@code n}): {@code d/dx x^n = n * x^(n-1)};
 *       chain-rule generalisation {@code d/dx u^n = n*u^(n-1) * u'},</li>
 *   <li>standard functions with chain rule:
 *       {@code sin(u) -> cos(u)*u'}, {@code cos(u) -> -sin(u)*u'},
 *       {@code exp(u) -> exp(u)*u'}, {@code log(u) -> u'/u}.</li>
 * </ul>
 *
 * <p>The output AST is not simplified beyond a tiny constant-folding pass
 * for {@code 0 + x}, {@code 1 * x} and friends, so downstream simplification
 * is the responsibility of {@link de.regelsuche.transform.AstRewriteTransformationEngine}
 * or callers that want canonical output.</p>
 */
public final class Differentiator {

    public Expr differentiate(Expr expr, String variable) {
        Objects.requireNonNull(expr, "expr");
        Objects.requireNonNull(variable, "variable");
        return simplify(diff(expr, variable));
    }

    private Expr diff(Expr expr, String variable) {
        if (expr instanceof NumberExpr) {
            return new NumberExpr(0);
        }
        if (expr instanceof VariableExpr varExpr) {
            return new NumberExpr(varExpr.name().equals(variable) ? 1 : 0);
        }
        if (expr instanceof BinaryExpr binary) {
            Expr u = binary.left();
            Expr v = binary.right();
            Expr du = diff(u, variable);
            Expr dv = diff(v, variable);
            return switch (binary.operator()) {
                case ADD -> new BinaryExpr(du, BinaryOperator.ADD, dv);
                case SUB -> new BinaryExpr(du, BinaryOperator.SUB, dv);
                case MUL -> new BinaryExpr(
                    new BinaryExpr(du, BinaryOperator.MUL, v),
                    BinaryOperator.ADD,
                    new BinaryExpr(u, BinaryOperator.MUL, dv)
                );
                case DIV -> new BinaryExpr(
                    new BinaryExpr(
                        new BinaryExpr(du, BinaryOperator.MUL, v),
                        BinaryOperator.SUB,
                        new BinaryExpr(u, BinaryOperator.MUL, dv)
                    ),
                    BinaryOperator.DIV,
                    new BinaryExpr(v, BinaryOperator.POW, new NumberExpr(2))
                );
                case POW -> powerRule(u, v, du, variable);
            };
        }
        if (expr instanceof FunctionExpr fn && fn.arguments().size() == 1) {
            Expr u = fn.arguments().get(0);
            Expr du = diff(u, variable);
            Expr outer = switch (fn.name()) {
                case "sin" -> new FunctionExpr("cos", u);
                case "cos" -> new BinaryExpr(
                    new NumberExpr(0),
                    BinaryOperator.SUB,
                    new FunctionExpr("sin", u)
                );
                case "exp" -> new FunctionExpr("exp", u);
                case "log", "ln" -> new BinaryExpr(new NumberExpr(1), BinaryOperator.DIV, u);
                default -> null;
            };
            if (outer != null) {
                return new BinaryExpr(outer, BinaryOperator.MUL, du);
            }
        }
        throw new UnsupportedOperationException(
            "Differentiator has no rule for " + expr.getClass().getSimpleName() + ": " + expr);
    }

    private Expr powerRule(Expr base, Expr exponent, Expr baseDerivative, String variable) {
        if (exponent instanceof NumberExpr n) {
            double power = n.value();
            // n * base^(n-1) * base'
            Expr coefficient = new NumberExpr(power);
            Expr reducedPower = new BinaryExpr(
                base,
                BinaryOperator.POW,
                new NumberExpr(power - 1)
            );
            Expr core = new BinaryExpr(coefficient, BinaryOperator.MUL, reducedPower);
            return new BinaryExpr(core, BinaryOperator.MUL, baseDerivative);
        }
        // d/dx u^v = u^v * (v' * ln u + v * u'/u)
        Expr dv = diff(exponent, variable);
        Expr lnBase = new FunctionExpr("ln", base);
        Expr first = new BinaryExpr(dv, BinaryOperator.MUL, lnBase);
        Expr second = new BinaryExpr(
            exponent,
            BinaryOperator.MUL,
            new BinaryExpr(baseDerivative, BinaryOperator.DIV, base)
        );
        Expr factor = new BinaryExpr(first, BinaryOperator.ADD, second);
        Expr power = new BinaryExpr(base, BinaryOperator.POW, exponent);
        return new BinaryExpr(power, BinaryOperator.MUL, factor);
    }

    /**
     * Lightweight constant-folding pass that simplifies the syntactic noise
     * inherent to the chain rule (e.g. {@code <expr> * 1 -> <expr>},
     * {@code 0 + x -> x}). It deliberately stays AST-local and does not
     * attempt full simplification.
     */
    public static Expr simplify(Expr expr) {
        if (expr instanceof BinaryExpr binary) {
            Expr left = simplify(binary.left());
            Expr right = simplify(binary.right());
            return foldBinary(left, binary.operator(), right);
        }
        if (expr instanceof FunctionExpr fn) {
            List<Expr> args = fn.arguments().stream().map(Differentiator::simplify).toList();
            return new FunctionExpr(fn.name(), args);
        }
        return expr;
    }

    private static Expr foldBinary(Expr left, BinaryOperator op, Expr right) {
        switch (op) {
            case ADD -> {
                if (isNumber(left, 0)) {
                    return right;
                }
                if (isNumber(right, 0)) {
                    return left;
                }
                if (left instanceof NumberExpr l && right instanceof NumberExpr r) {
                    return new NumberExpr(l.value() + r.value());
                }
            }
            case SUB -> {
                if (isNumber(right, 0)) {
                    return left;
                }
                if (left instanceof NumberExpr l && right instanceof NumberExpr r) {
                    return new NumberExpr(l.value() - r.value());
                }
            }
            case MUL -> {
                if (isNumber(left, 0) || isNumber(right, 0)) {
                    return new NumberExpr(0);
                }
                if (isNumber(left, 1)) {
                    return right;
                }
                if (isNumber(right, 1)) {
                    return left;
                }
                if (left instanceof NumberExpr l && right instanceof NumberExpr r) {
                    return new NumberExpr(l.value() * r.value());
                }
            }
            case DIV -> {
                if (isNumber(right, 1)) {
                    return left;
                }
                if (isNumber(left, 0)) {
                    return new NumberExpr(0);
                }
            }
            case POW -> {
                if (isNumber(right, 1)) {
                    return left;
                }
                if (isNumber(right, 0)) {
                    return new NumberExpr(1);
                }
                if (isNumber(left, 1)) {
                    return new NumberExpr(1);
                }
            }
            default -> {
            }
        }
        return new BinaryExpr(left, op, right);
    }

    private static boolean isNumber(Expr expr, double value) {
        return expr instanceof NumberExpr number && number.value() == value;
    }
}
