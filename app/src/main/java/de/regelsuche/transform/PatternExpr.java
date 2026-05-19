package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.Map;
import java.util.Optional;

public sealed interface PatternExpr permits PatternExpr.Placeholder, PatternExpr.LiteralNumber, PatternExpr.Operation {
    static PatternExpr var(String name) {
        return new Placeholder(name);
    }

    static PatternExpr num(double value) {
        return new LiteralNumber(value);
    }

    static PatternExpr op(BinaryOperator operator, PatternExpr left, PatternExpr right) {
        return new Operation(operator, left, right);
    }

    boolean match(Expr expression, Map<String, Expr> bindings);

    Expr instantiate(Map<String, Expr> bindings);

    record Placeholder(String name) implements PatternExpr {
        public Placeholder {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("placeholder name must not be blank");
            }
        }

        @Override
        public boolean match(Expr expression, Map<String, Expr> bindings) {
            Expr bound = bindings.get(name);
            if (bound == null) {
                bindings.put(name, expression);
                return true;
            }
            return bound.equals(expression);
        }

        @Override
        public Expr instantiate(Map<String, Expr> bindings) {
            return Optional.ofNullable(bindings.get(name))
                .orElseThrow(() -> new IllegalArgumentException("Missing binding for " + name));
        }
    }

    record LiteralNumber(double value) implements PatternExpr {
        @Override
        public boolean match(Expr expression, Map<String, Expr> bindings) {
            return expression instanceof NumberExpr numberExpr && numberExpr.value() == value;
        }

        @Override
        public Expr instantiate(Map<String, Expr> bindings) {
            return new NumberExpr(value);
        }
    }

    record Operation(BinaryOperator operator, PatternExpr left, PatternExpr right) implements PatternExpr {
        public Operation {
            if (operator == null || left == null || right == null) {
                throw new IllegalArgumentException("operator, left and right must not be null");
            }
        }

        @Override
        public boolean match(Expr expression, Map<String, Expr> bindings) {
            if (!(expression instanceof BinaryExpr binaryExpr) || binaryExpr.operator() != operator) {
                return false;
            }
            return left.match(binaryExpr.left(), bindings) && right.match(binaryExpr.right(), bindings);
        }

        @Override
        public Expr instantiate(Map<String, Expr> bindings) {
            return new BinaryExpr(left.instantiate(bindings), operator, right.instantiate(bindings));
        }
    }
}
